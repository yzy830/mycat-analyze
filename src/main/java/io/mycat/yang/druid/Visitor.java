package io.mycat.yang.druid;

import io.mycat.route.parser.druid.MycatSchemaStatVisitor;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;

/**
 * 从测试看
 * (1) {@link SchemaStatVisitor#getAliasMap()}是从别名到真名的映射，包括表的别名和select column的别名
 * (2) 如果存在or条件，or条件会存放在whereUnit中，condition中只会存放and条件
 * (3) tableStat表示每个表参与的运算，例如select/update等
 */
public class Visitor {
    public static void main(String[] args) {
    	String sql = "select o.order_id as orderId, max(o.order_sum) as orderSum, upper(uv.user_name) userName from t_d_order o "
                + "join (select u.user_id, u.user_name,u.user_gender from t_d_user u join t_d_user_detail ud on u.user_id = ud.user_id) uv on o.user_id = uv.user_id "
                + "where o.order_sum > 100 and uv.user_name in ('a', 'b') group by o.user_id having max(o.order_sum) > 200 order by uv.gender desc limit 0,10";
        
        MySqlStatementParser parser = new MySqlStatementParser(sql);
        
        SQLStatement statement = parser.parseStatement();
        
        MySqlSchemaStatVisitor visitor = new MySqlSchemaStatVisitor();
        statement.accept(visitor);
        
        System.out.println("columns = " + visitor.getColumns());
        System.out.println("conditions = " + visitor.getConditions());
        System.out.println("aggregates = " + visitor.getAggregateFunctions());
        System.out.println("funtions = " + visitor.getFunctions());
        System.out.println("groupByColumns = " + visitor.getGroupByColumns());
        System.out.println("orderByColumns = " + visitor.getOrderByColumns());
        System.out.println("parameters = " + visitor.getParameters());
        System.out.println("relations = " + visitor.getRelationships());
        System.out.println("tables = " + visitor.getTables());
        System.out.println("currentTable = " + visitor.getCurrentTable());
        System.out.println("aliasMap = " + visitor.getAliasMap());
        System.out.println("variants = " + visitor.getVariants());
    	
//        String sql = "select e.eventId, e.eventKey, e.eventName, e.flag from "
//                + "event e join user u where eventId = ? and eventKey = ? and (eventName = ? or eventName = ?) "
//                + "and u.user_id = 1 and e.event_time = ? group by u.user_id order by eventName";
//        MySqlStatementParser parser = new MySqlStatementParser(sql); 
//        SQLStatement statement = parser.parseStatement(); 
//        MycatSchemaStatVisitor visitor = new MycatSchemaStatVisitor(); 
//        
//        statement.accept(visitor);
    }
}
