package io.mycat.yang.druid;

import io.mycat.route.parser.druid.MycatSchemaStatVisitor;
import io.mycat.route.parser.druid.WhereUnit;

import java.util.List;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;

public class WhereUnitTest {
    public static void main(String[] args) {
        String sql = "select * from t_d_order where status = 'Y' and "
                + "((order_type = 'O2O' and (order_id = 1 or order_id = 2)) or "
                + " (order_type = 'B2C' and (order_id = 3 or order_id = 4))) and (pay_status = 'PAYED')";
        
        MySqlStatementParser parser = new MySqlStatementParser(sql);
        
        SQLStatement statement = parser.parseStatement();
        
        MycatSchemaStatVisitor visitor = new MycatSchemaStatVisitor(); 
        statement.accept(visitor);
        
        visitor.splitConditions();
        
        List<WhereUnit> units = visitor.getStoredWhereUnits();
        
        print(units, 0);
    }
    
    private static void print(List<WhereUnit> units, int level) {
        for(WhereUnit unit : units) {
            for(int i = 0; i < 2 * level; ++i) {
                System.out.print("*");
            }
            
            String unitInfo = format(unit);
            System.out.println("{" + unitInfo + "}");
            
            if(unit.getSubWhereUnit() != null && unit.getSubWhereUnit().size() > 0) {
                print(unit.getSubWhereUnit(), level + 1);
            }
        }
    }
    
    private static String format(WhereUnit unit) {
        StringBuilder builder = new StringBuilder();
        
        builder.append("whereExpr = ").append(unit.getWhereExpr()).append(",")
               .append("outConditions = ").append(unit.getOutConditions()).append(",")
               .append("splitedExpr = ").append(unit.getSplitedExprList())
               ;
        
        return builder.toString();
    }
}
