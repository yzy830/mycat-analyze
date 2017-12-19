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

        
//        String sql = "select * from t_d_order where (order_type_code = 'O2O' and (order_id = 1 or order_id = 3)) or "
//                + "(order_type_code = 'B2C' and order_id = 2) or "
//                + "(order_type_code = 'TAKE_OUT' and (order_id = 4 or order_id = 8))";
        
//        String sql = "select * from t_d_order where status = 'Y' and (((order_id = 1 or order_id = 3) and "
//                + "(order_type_code = 'B2C' or (order_id = 2 or order_id = 9))) or "
//                + "(order_id = 4 or order_id = 8))";
        
//        String sql = "select * from t_d_order_base ob join t_d_order_refund oref on ob.order_id = oref.order_id where "
//                + " (oref.status = 'Y' and (oref.order_refund_id = 97272926969004140 or oref.order_refund_id = 88998781407723752))"
//                + " or"
//                + " (ob.status = 'Y' and (ob.order_id = 86099032245534873 or ob.order_id = 86098918500204697))";
        
        String sql = "select * from t_d_order_base ob where order_id = 1 and order_id = 2 and order_id in (3,4,5)";
        
        MySqlStatementParser parser = new MySqlStatementParser(sql);
        
        SQLStatement statement = parser.parseStatement();
        
        MycatSchemaStatVisitor visitor = new MycatSchemaStatVisitor(); 
        statement.accept(visitor);
        
        System.out.println("conditions = " + visitor.getConditions());
        System.out.println("splitConditions = " + visitor.splitConditions());
        System.out.println("columns = " + visitor.getColumns());
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
