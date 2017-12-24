package io.mycat.memory.unsafe.row;

import io.mycat.sqlengine.mpp.ColMeta;
import io.mycat.sqlengine.mpp.OrderCol;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Created by zagnix on 2016/6/6.
 * 
 * yzy: 记录了结果集各列的基本元数据
 */
public class StructType {

    /**
     * 结果集别名到元数据映射(列序号、类型)
     */
    private final Map<String, ColMeta> columToIndx;
    /**
     * 结果集列数量
     */
    private final int fieldCount;

    /**
     * 排序字段
     */
    private  OrderCol[] orderCols = null;

    public StructType(@Nonnull Map<String,ColMeta> columToIndx,int fieldCount){
        assert fieldCount >=0;
        this.columToIndx = columToIndx;
        this.fieldCount = fieldCount;
    }

    public int length() {
        return fieldCount;
    }

    public Map<String, ColMeta> getColumToIndx() {
        return columToIndx;
    }

    public OrderCol[] getOrderCols() {
        return orderCols;
    }

    public void setOrderCols(OrderCol[] orderCols) {
        this.orderCols = orderCols;
    }

    public long apply(int i) {
        return 0;
    }
}
