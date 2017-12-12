package io.mycat.yang.druid;

import io.mycat.route.parser.druid.MycatSchemaStatVisitor;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;

public class Visitor {
    public static void main(String[] args) {
//        String sql = "select * from t_d_order where (((order_type = 'O2O' and (user_id = 1 or user_id = 3)) or "
//                + "(order_type = 'B2C' and user_id = 2)) or "
//                + "(order_sum > 100 or (order_sum < 50 and user_id = 4 and (order_id = 1 or order_id = 2)))) "
//                + "and order_status = 'Y'";
        String sql = "select * from t_d_order where (order_type_code = 'O2O' and (order_id = 1 or order_id = 3)) or "
                + "(order_type_code = 'B2C' and order_id = 2) or "
                + "(order_type_code = 'TAKE_OUT' and (order_id = 4 or order_id = 8))";
        
        MySqlStatementParser parser = new MySqlStatementParser(sql);
        
        SQLStatement statement = parser.parseStatement();
        
        MycatSchemaStatVisitor visitor = new MycatSchemaStatVisitor(); 
        statement.accept(visitor);
        
        System.out.println("splitConditions = " + visitor.splitConditions());
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
        System.out.println("whereUnits = " + visitor.getWhereUnits());
        
    }
}
