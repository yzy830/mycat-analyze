/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese
 * opensource volunteers. you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Any questions about this component can be directed to it's project Web address
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.route;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

import io.mycat.server.parser.ServerParse;
import io.mycat.sqlengine.mpp.LoadData;

/**
 * 代表一个数据路由节点。一个{@link RouteResultset}由一组{@code RouteResultsetNode}构成。
 * 
 * <p>
 * {@link #canRunInReadDB}等信息来自于{@link RouteResultset}。
 * </p>
 * 
 * @author mycat
 */
public final class RouteResultsetNode implements Serializable , Comparable<RouteResultsetNode> {
	/**
	 *
	 */
	private static final long serialVersionUID = 1L;
	private final String name; // 数据节点名称
	/**
	 * 记录实际要执行的语句。因为存在sql改写，因此statement可能与srcStatment不一样
	 */
	private String statement; // 执行的语句
	/**
	 * yzy: 创建RouteResultsetNode时记录的sql语句。
	 */
	private final String srcStatement;
	private final int sqlType;
	/**
	 * 这个值是从RouteResultset拷贝过来的，表示是否可以在read db上允许。因为只有SELECT语句情况下，
	 * RouteResultset才设置这个值，因此在其他语句中，canRunInReadDB都是false
	 */
	private volatile boolean canRunInReadDB;
	/**
	 * 如果sql使用了balance注解，这个值为true
	 */
	private final boolean hasBlanceFlag;
    private boolean callStatement = false; // 处理call关键字
	/**
	 * 记录在这个节点上，分页的偏移量
	 */
	private int limitStart;
	/**
	 * 记录在这个节点上，分页的大小。例如，在跨分片的时候，需要10~20之间的数据，但是每个RouteResultsetNode上，需要取0~20之间的数据
	 */
	private int limitSize;
	private int totalNodeSize =0; //方便后续jdbc批量获取扩展
   private Procedure procedure;
	private LoadData loadData;
	private RouteResultset source;
	
	// 强制走 master，可以通过 RouteResultset的属性canRunInReadDB(false)
	// 传给 RouteResultsetNode 来实现，但是 强制走 slave需要增加一个属性来实现:
	private Boolean runOnSlave = null;	// 默认null表示不施加影响, true走slave,false走master
	
	/**
	 * 只有使用subTables这个配置的时候，这个字段才有用，其他情况下都是NULL。这个特性我们不会用到
	 */
	private String subTableName; // 分表的表名

	//迁移算法用     -2代表不是slot分片  ，-1代表扫描所有分片
	private int slot=-2;
	
	public RouteResultsetNode(String name, int sqlType, String srcStatement) {
		this.name = name;
		limitStart=0;
		this.limitSize = -1;
		this.sqlType = sqlType;
		this.srcStatement = srcStatement;
		this.statement = srcStatement;
		canRunInReadDB = (sqlType == ServerParse.SELECT || sqlType == ServerParse.SHOW);
		hasBlanceFlag = (statement != null)
				&& statement.startsWith("/*balance*/");
	}

	public Boolean getRunOnSlave() {
		return runOnSlave;
	}

	public void setRunOnSlave(Boolean runOnSlave) {
		this.runOnSlave = runOnSlave;
	}
	  private Map hintMap;

    public Map getHintMap()
    {
        return hintMap;
    }

    public void setHintMap(Map hintMap)
    {
        this.hintMap = hintMap;
    }

	public void setStatement(String statement) {
		this.statement = statement;
	}

	public void setCanRunInReadDB(boolean canRunInReadDB) {
		this.canRunInReadDB = canRunInReadDB;
	}

	public boolean getCanRunInReadDB() {
		return this.canRunInReadDB;
	}

	public void resetStatement() {
		this.statement = srcStatement;
	}

	/**
	 * <p>
	 * 这是mycat判断主从的原则，后面有必要测试一下，可能会影响java的代码风格。mycat只有在autocommit为true的情况下或者在使用了\/*balance*\/注解
	 * 是，才会对select/show语句使用slave。那么，如果使用@Transactional注解，autocommit会被关闭，在大部分情况下，都会走到master
	 * </p>
	 * 
	 * 这里的逻辑是为了优化，实现：非业务sql可以在负载均衡走slave的效果。因为业务sql一般是非自动提交，
	 * 而非业务sql一般默认是自动提交，比如mysql client，还有SQLJob, heartbeat都可以使用
	 * 了Leader-us优化的query函数，该函数实现为自动提交；
	 * 
	 * 在非自动提交的情况下(有事物)，除非使用了  balance 注解的情况下，才可以走slave.
	 * 
	 * 当然还有一个大前提，必须是 select 或者 show 语句(canRunInReadDB=true)
	 * @param autocommit
	 * @return
	 */
	public boolean canRunnINReadDB(boolean autocommit) {
		return canRunInReadDB && ( autocommit || (!autocommit && hasBlanceFlag) );
	}
	
//	public boolean canRunnINReadDB(boolean autocommit) {
//		return canRunInReadDB && autocommit && !hasBlanceFlag
//			|| canRunInReadDB && !autocommit && hasBlanceFlag;
//	}
  public Procedure getProcedure()
    {
        return procedure;
    }

	public int getSlot() {
		return slot;
	}

	public void setSlot(int slot) {
		this.slot = slot;
	}

	public void setProcedure(Procedure procedure)
    {
        this.procedure = procedure;
    }

    public boolean isCallStatement()
    {
        return callStatement;
    }

    public void setCallStatement(boolean callStatement)
    {
        this.callStatement = callStatement;
    }
	public String getName() {
		return name;
	}

	public int getSqlType() {
		return sqlType;
	}

	public String getStatement() {
		return statement;
	}

	public int getLimitStart()
	{
		return limitStart;
	}

	public void setLimitStart(int limitStart)
	{
		this.limitStart = limitStart;
	}

	public int getLimitSize()
	{
		return limitSize;
	}

	public void setLimitSize(int limitSize)
	{
		this.limitSize = limitSize;
	}

	public int getTotalNodeSize()
	{
		return totalNodeSize;
	}

	public void setTotalNodeSize(int totalNodeSize)
	{
		this.totalNodeSize = totalNodeSize;
	}

	public LoadData getLoadData()
	{
		return loadData;
	}

	public void setLoadData(LoadData loadData)
	{
		this.loadData = loadData;
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj instanceof RouteResultsetNode) {
			RouteResultsetNode rrn = (RouteResultsetNode) obj;
			if(subTableName!=null){
				if (equals(name, rrn.getName()) && equals(subTableName, rrn.getSubTableName())) {
					return true;
				}
			}else{
				if (equals(name, rrn.getName())) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append(name);
		s.append('{').append(statement).append('}');
		return s.toString();
	}

	private static boolean equals(String str1, String str2) {
		if (str1 == null) {
			return str2 == null;
		}
		return str1.equals(str2);
	}

	public String getSubTableName() {
		return this.subTableName;
	}

	public void setSubTableName(String subTableName) {
		this.subTableName = subTableName;
	}

	public boolean isModifySQL() {
		return !canRunInReadDB;
	}
	public boolean isDisctTable() {
		if(subTableName!=null && !subTableName.equals("")){
			return true;
		};
		return false;
	}
	

	@Override
	public int compareTo(RouteResultsetNode obj) {
		if(obj == null) {
			return 1;
		}
		if(this.name == null) {
			return -1;
		}
		if(obj.name == null) {
			return 1;
		}
		int c = this.name.compareTo(obj.name);
		if(!this.isDisctTable()){
			return c;
		}else{
			if(c==0){
				return this.subTableName.compareTo(obj.subTableName);
			}
			return c;
		}
	}
	
	public boolean isHasBlanceFlag() {
		return hasBlanceFlag;
	}

	public RouteResultset getSource() {
		return source;
	}

	public void setSource(RouteResultset source) {
		this.source = source;
	}
}
