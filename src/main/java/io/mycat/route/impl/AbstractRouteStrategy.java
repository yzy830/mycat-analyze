package io.mycat.route.impl;

import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;

import org.slf4j.Logger; import org.slf4j.LoggerFactory;

import io.mycat.MycatServer;
import io.mycat.cache.LayerCachePool;
import io.mycat.config.model.SchemaConfig;
import io.mycat.config.model.SystemConfig;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteStrategy;
import io.mycat.route.util.RouterUtil;
import io.mycat.server.ServerConnection;
import io.mycat.server.parser.ServerParse;
import io.mycat.sqlengine.mpp.LoadData;

public abstract class AbstractRouteStrategy implements RouteStrategy {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRouteStrategy.class);

	/**
	 * 执行sql路由，在路由过程中，大部门都统一走{@link #routeNormalSqlWithAST(SchemaConfig, String, RouteResultset, String, LayerCachePool)}，做统一处理。
	 * 只有几种情况例外
	 * <ol>
	 *   <li>ddl：ddl一般需要路由到所有节点</li>
	 *   <li>需要mycat生成序列</li>
	 *   <li>在insert的时候，需要mycat生成自增主键</li>
	 *   <li>在insert情况下，处理ER路由</li>
	 * </ol>
	 * 
	 * 其他情况，无论是select/insert/update/delete，都是用相同的路由逻辑。一般，select的情况是最复杂的，可以先分析这种情况，后面再补充
	 * insert/update/delete的情况
	 * */
	@Override
	public RouteResultset route(SystemConfig sysConfig, SchemaConfig schema, int sqlType, String origSQL,
			String charset, ServerConnection sc, LayerCachePool cachePool) throws SQLNonTransientException {

		//对应schema标签checkSQLschema属性，把表示schema的字符去掉
		if (schema.isCheckSQLSchema()) {
			origSQL = RouterUtil.removeSchema(origSQL, schema.getName());
		}

		/**
         * 处理一些路由之前的逻辑
         * 全局序列号，父子表插入
         * 
         * yzy: 对注解的处理在这个之前。因此，如果语句中有mycat:sql = 注解，序列生成、ER表、自增主键生成均不生效。
         * 
         * sql注解路由时，不会处理原始sql语句，因此对语句不会有任何改写。因此，如果sql中存在聚合方法、order by、group by、having、limit
         * 等需要改写的方法时，且注解导致多分片路由时，结果均错误
         */
		if ( beforeRouteProcess(schema, sqlType, origSQL, sc) ) {
			return null;
		}

		/**
		 * SQL 语句拦截
		 */
		String stmt = MycatServer.getInstance().getSqlInterceptor().interceptSQL(origSQL, sqlType);
		if (!origSQL.equals(stmt) && LOGGER.isDebugEnabled()) {
			LOGGER.debug("sql intercepted to " + stmt + " from " + origSQL);
		}


		RouteResultset rrs = new RouteResultset(stmt, sqlType);

		/**
		 * 优化debug loaddata输出cache的日志会极大降低性能
		 */
		if (LOGGER.isDebugEnabled() && origSQL.startsWith(LoadData.loadDataHint)) {
			rrs.setCacheAble(false);
		}

        /**
         * rrs携带ServerConnection的autocommit状态用于在sql解析的时候遇到
         * select ... for update的时候动态设定RouteResultsetNode的canRunInReadDB属性
         */
		if (sc != null ) {
			rrs.setAutocommit(sc.isAutocommit());
		}

		/**
		 * DDL 语句的路由
		 */
		if (ServerParse.DDL == sqlType) {
			return RouterUtil.routeToDDLNode(rrs, sqlType, stmt, schema);
		}

		/**
		 * 检查是否有分片
		 */
		if (schema.isNoSharding() && ServerParse.SHOW != sqlType) {
			rrs = RouterUtil.routeToSingleNode(rrs, schema.getDataNode(), stmt);
		} else {
			RouteResultset returnedSet = routeSystemInfo(schema, sqlType, stmt, rrs);
			if (returnedSet == null) {
				rrs = routeNormalSqlWithAST(schema, stmt, rrs, charset, cachePool);
			}
		}

		return rrs;
	}

	/**
	 * 路由之前必要的处理
	 * 主要是全局序列号插入，还有子表插入
	 */
	private boolean beforeRouteProcess(SchemaConfig schema, int sqlType, String origSQL, ServerConnection sc)
			throws SQLNonTransientException {
		
		return RouterUtil.processWithMycatSeq(schema, sqlType, origSQL, sc)
				|| (sqlType == ServerParse.INSERT && RouterUtil.processERChildTable(schema, origSQL, sc))
				|| (sqlType == ServerParse.INSERT && RouterUtil.processInsert(schema, sqlType, origSQL, sc));
	}

	/**
	 * 通过解析AST语法树类来寻找路由
	 */
	public abstract RouteResultset routeNormalSqlWithAST(SchemaConfig schema, String stmt, RouteResultset rrs,
			String charset, LayerCachePool cachePool) throws SQLNonTransientException;

	/**
	 * 路由信息指令, 如 SHOW、SELECT@@、DESCRIBE
	 */
	public abstract RouteResultset routeSystemInfo(SchemaConfig schema, int sqlType, String stmt, RouteResultset rrs)
			throws SQLSyntaxErrorException;

	/**
	 * 解析 Show 之类的语句
	 */
	public abstract RouteResultset analyseShowSQL(SchemaConfig schema, RouteResultset rrs, String stmt)
			throws SQLNonTransientException;

}
