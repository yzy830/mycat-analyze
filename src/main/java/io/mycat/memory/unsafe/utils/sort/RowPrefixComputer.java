package io.mycat.memory.unsafe.utils.sort;


import io.mycat.memory.unsafe.row.StructType;
import io.mycat.memory.unsafe.row.UnsafeRow;
import io.mycat.memory.unsafe.utils.BytesTools;
import io.mycat.sqlengine.mpp.ColMeta;
import io.mycat.sqlengine.mpp.OrderCol;
import io.mycat.util.ByteUtil;
import io.mycat.util.IntegerUtil;
import io.mycat.util.LongUtil;

import javax.annotation.Nonnull;
import java.io.UnsupportedEncodingException;

/**
 * Created by zagnix on 2016/6/20.
 */
public class RowPrefixComputer extends UnsafeExternalRowSorter.PrefixComputer {
    @Nonnull
    private final StructType schema;
    private final ColMeta colMeta;

    public RowPrefixComputer(StructType schema){
        this.schema = schema;
        /**
         * 通过计算得到排序关键词的第一个在行的索引下标
         */
        int orderIndex = 0;
        OrderCol[] orderCols = schema.getOrderCols();

        if (orderCols != null){
        	/*
        	 * yzy：这个代码很奇怪，可能存在Bug，需要测试。
        	 * 
        	 * 确实存在BUG。这个循环不应该存在，至少对于MySQL是不应该存在的。
        	 * 
        	 * 按照当前的处理逻辑，MyCat在排序的时候，先进行了一次前缀比较；
        	 * 在前缀比较相同的情况下，再执行全行比较。因此，这里只能选择order by
        	 * 的第一个行，用于计算前缀，而与select列表的顺序没有关系。
        	 * 
        	 * 在构造前缀计算器之后，还需要构造前缀比较器(在DataNodeMergeManager中)，
        	 * 在构造前缀比较器的时候，MyCat又使用了第一个排序列的排序方式选择比较器(ASC/DESC)。
        	 * 
        	 * 例如，
        	 * select user_id, order_id, order_sum  from t_d_order order by order_sum desc, user_id asc
        	 * 按照当前的处理，会按照user_id降序排列，如果user_id相同，再按照order by列表计算		
        	 * */
            for (int i = 0; i < orderCols.length; i++) {
                ColMeta colMeta = orderCols[i].colMeta;
                if(colMeta.colIndex == 0){
                    orderIndex = i;
                    break;
                }
            }

            this.colMeta = orderCols[orderIndex].colMeta;
        }else {
            this.colMeta = null;
        }
    }

    protected long computePrefix(UnsafeRow row) throws UnsupportedEncodingException {

        if(this.colMeta == null){
            return 0;
        }

        int orderIndexType = colMeta.colType;

        byte[] rowIndexElem  = null;
		
		  if(!row.isNullAt(colMeta.colIndex)) {
              rowIndexElem = row.getBinary(colMeta.colIndex);;
          }else {
              rowIndexElem = new byte[1];
              rowIndexElem[0] = UnsafeRow.NULL_MARK;
          }
		  
        /**
         * 这里注意一下，order by 排序的第一个字段
         */
        switch (orderIndexType) {
            case ColMeta.COL_TYPE_INT:
            case ColMeta.COL_TYPE_LONG:
            case ColMeta.COL_TYPE_INT24:
                return BytesTools.getInt(rowIndexElem);
            case ColMeta.COL_TYPE_SHORT:
                return BytesTools.getShort(rowIndexElem);
            case ColMeta.COL_TYPE_LONGLONG:
                return BytesTools.getLong(rowIndexElem);
            case ColMeta.COL_TYPE_FLOAT:
                return PrefixComparators.DoublePrefixComparator.
                    computePrefix(BytesTools.getFloat(rowIndexElem));
            case ColMeta.COL_TYPE_DOUBLE:
            case ColMeta.COL_TYPE_DECIMAL:
            case ColMeta.COL_TYPE_NEWDECIMAL:
                return PrefixComparators.DoublePrefixComparator.
                        computePrefix(BytesTools.getDouble(rowIndexElem));
            case ColMeta.COL_TYPE_DATE:
            case ColMeta.COL_TYPE_TIMSTAMP:
            case ColMeta.COL_TYPE_TIME:
            case ColMeta.COL_TYPE_YEAR:
            case ColMeta.COL_TYPE_DATETIME:
            case ColMeta.COL_TYPE_NEWDATE:
            case ColMeta.COL_TYPE_BIT:
            case ColMeta.COL_TYPE_VAR_STRING:
            case ColMeta.COL_TYPE_STRING:
                // ENUM和SET类型都是字符串，按字符串处理
            case ColMeta.COL_TYPE_ENUM:
            case ColMeta.COL_TYPE_SET:
                return PrefixComparators.BinaryPrefixComparator.computePrefix(rowIndexElem);
               //BLOB相关类型和GEOMETRY类型不支持排序，略掉
        }
        return 0;
    }
}
