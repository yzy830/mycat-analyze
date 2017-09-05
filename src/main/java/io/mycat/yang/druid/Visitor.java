package io.mycat.yang.druid;

import io.mycat.route.parser.druid.MycatSchemaStatVisitor;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;

/**
 * 从测试看
 * (1) {@link SchemaStatVisitor#getAliasMap()}是从别名到真名的映射，包括表的别名和select column的别名
 * (2) 如果存在or条件，or条件会存放在whereUnit中，condition中只会存放and条件
 * (3) tableStat表示每个表参与的运算，例如select/update等
 */
public class Visitor {
    public static void main(String[] args) {
        String sql = "select e.eventId, e.eventKey, e.eventName, e.flag from "
                + "event e join user u where eventId = ? and eventKey = ? and (eventName = ? or eventName = ?) "
                + "and u.user_id = 1 and e.event_time = ? group by u.user_id order by eventName";
        MySqlStatementParser parser = new MySqlStatementParser(sql); 
        SQLStatement statement = parser.parseStatement(); 
        MycatSchemaStatVisitor visitor = new MycatSchemaStatVisitor(); 
        
        statement.accept(visitor);
    }
}
