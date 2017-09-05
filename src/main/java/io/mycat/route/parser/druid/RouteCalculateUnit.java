package io.mycat.route.parser.druid;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.mycat.sqlengine.mpp.ColumnRoutePair;
import io.mycat.sqlengine.mpp.RangeValue;

/**
 * 路由计算单元。
 * 
 * <p>
 * mycat的路由计算单元是表，{@code RouteCalculateUnit}中的核心数据是一个 map {@link #tablesAndConditions}。
 * 其key是表名，value是列、值对。
 * </p>
 * 
 * <p>
 * {@code tablesAndConditions}的每一个value也是一个map结构，其key实一个列，value是一个set接口，是这个列可取的值
 * </p>
 * 
 * <p>
 * 一个查询语句中，如果存在OR条件，则会拆分为多个RouteCalculateUnit。每个unit单独计算分片，然后求一个并集
 * </p>
 * 
 * @author wang.dw
 * @date 2015-3-14 下午6:24:54
 * @version 0.1.0 
 * @copyright wonhigh.cn
 */
public class RouteCalculateUnit {
	private Map<String, Map<String, Set<ColumnRoutePair>>> tablesAndConditions = new LinkedHashMap<String, Map<String, Set<ColumnRoutePair>>>();

	public Map<String, Map<String, Set<ColumnRoutePair>>> getTablesAndConditions() {
		return tablesAndConditions;
	}

	public void addShardingExpr(String tableName, String columnName, Object value) {
		Map<String, Set<ColumnRoutePair>> tableColumnsMap = tablesAndConditions.get(tableName);
		
		if (value == null) {
			// where a=null
			return;
		}
		
		if (tableColumnsMap == null) {
			tableColumnsMap = new LinkedHashMap<String, Set<ColumnRoutePair>>();
			tablesAndConditions.put(tableName, tableColumnsMap);
		}
		
		String uperColName = columnName.toUpperCase();
		Set<ColumnRoutePair> columValues = tableColumnsMap.get(uperColName);

		if (columValues == null) {
			columValues = new LinkedHashSet<ColumnRoutePair>();
			tablesAndConditions.get(tableName).put(uperColName, columValues);
		}

		if (value instanceof Object[]) {
			for (Object item : (Object[]) value) {
				if(item == null) {
					continue;
				}
				columValues.add(new ColumnRoutePair(item.toString()));
			}
		} else if (value instanceof RangeValue) {
			columValues.add(new ColumnRoutePair((RangeValue) value));
		} else {
			columValues.add(new ColumnRoutePair(value.toString()));
		}
	}
	
	public void clear() {
		tablesAndConditions.clear();
	}
	
	
}
