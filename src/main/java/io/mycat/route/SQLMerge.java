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
import java.util.LinkedHashMap;
import java.util.Map;

import io.mycat.sqlengine.mpp.HavingCols;

public class SQLMerge implements Serializable {
	/**
	 * yzy: 排序列，排序方式映射。已经经过了select列表别名的映射
	 */
	private LinkedHashMap<String, Integer> orderByCols;
	/**
	 * yzy: having子句信息
	 */
	private HavingCols havingCols;
	/**
	 * yzy：这个东西感觉存储的有点奇怪。估计是有BUG
	 * 
	 * 当前存储了select每个聚合项的别名，与mergeCols类似，但是存储这个的意义是什么？
	 */
	private Object[] havingColsName;			// Added by winbill, 20160314, for having clause
	/**
	 * yzy: 存储select列表中，聚合项与聚合方法之间的映射关系
	 */
	private Map<String, Integer> mergeCols;
	/**
	 * yzy: 存储group by子句的列。已经经过了select列表别名的映射
	 */
	private String[] groupByCols;
	private boolean hasAggrColumn;

	public LinkedHashMap<String, Integer> getOrderByCols() {
		return orderByCols;
	}

	public void setOrderByCols(LinkedHashMap<String, Integer> orderByCols) {
		this.orderByCols = orderByCols;
	}

	public Map<String, Integer> getMergeCols() {
		return mergeCols;
	}

	public void setMergeCols(Map<String, Integer> mergeCols) {
		this.mergeCols = mergeCols;
	}

	public String[] getGroupByCols() {
		return groupByCols;
	}

	public void setGroupByCols(String[] groupByCols) {
		this.groupByCols = groupByCols;
	}

	public boolean isHasAggrColumn() {
		return hasAggrColumn;
	}

	public void setHasAggrColumn(boolean hasAggrColumn) {
		this.hasAggrColumn = hasAggrColumn;
	}

	public HavingCols getHavingCols() {
		return havingCols;
	}

	public void setHavingCols(HavingCols havingCols) {
		this.havingCols = havingCols;
	}

	public Object[] getHavingColsName() {
		return havingColsName;
	}

	public void setHavingColsName(Object[] havingColsName) {
		this.havingColsName = havingColsName;
	}
}