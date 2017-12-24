

package io.mycat.memory.unsafe.utils.sort;

import com.google.common.primitives.UnsignedLongs;
import io.mycat.memory.unsafe.types.ByteArray;


public class PrefixComparators {
  private PrefixComparators() {}

  public static final PrefixComparator STRING = new UnsignedPrefixComparator();
  public static final PrefixComparator STRING_DESC = new UnsignedPrefixComparatorDesc();
  public static final PrefixComparator BINARY = new UnsignedPrefixComparator();
  public static final PrefixComparator BINARY_DESC = new UnsignedPrefixComparatorDesc();
  public static final PrefixComparator LONG = new SignedPrefixComparator();
  public static final PrefixComparator LONG_DESC = new SignedPrefixComparatorDesc();
  public static final PrefixComparator DOUBLE = new UnsignedPrefixComparator();
  public static final PrefixComparator DOUBLE_DESC = new UnsignedPrefixComparatorDesc();

  public static final PrefixComparator RadixSortDemo = new RadixSortDemo();



  public static final class BinaryPrefixComparator {
    public static long computePrefix(byte[] bytes) {
      return ByteArray.getPrefix(bytes);
    }
  }

  public static final class DoublePrefixComparator {
    /**
     * Converts the double into a value that compares correctly as an unsigned long. For more
     * details see http://stereopsis.com/radix.html.
     * 
     * <p>
     *   yzy: 这里对double的处理，涉及到内存中是复合表示一个double的。
     * </p>
     * 
     * <p>
     *   yzy: double用一个long表示，可以分为3段。bit63，符号位；bit62~52，指数位；bit51~0，数值位。
     *   如果数值位0xffffffffffffff，表示数值位1.ffffffffffffff
     * </p>
     * 
     * <p>
     *   指数位如果是0x7ff，表示无效值，0x7ff0000000000000L表示正无穷；0xfff0000000000000L表示负无穷；
     *   其他表示NaN，doubleToLongBits将所有的NaN表示为0x7ff8000000000000L
     * </p>
     * 
     * <p>
     *  指数位的有效取值范围是0x001~0x7fe。取值范围是-1022~1023,
     * </p>
     */
    public static long computePrefix(double value) {
      // Java's doubleToLongBits already canonicalizes all NaN values to the smallest possible
      // positive NaN, so there's nothing special we need to do for NaNs.
      long bits = Double.doubleToLongBits(value);
      // Negative floats compare backwards due to their sign-magnitude representation, so flip
      // all the bits in this case.
      /*
       * 这个地方的处理时错误的，其基本思想是：将负数安慰取反。因为，一旦转换为long表示，计算机就会按照
       * 补码来解释数值，而补码表示中，long的绝对值越大，值就越大，这一点和double原本表示的意思是相反的。
       * 但是，补码转换需要保证整数不变，而负数的非符号位按位取反。
       * 
       * 这里的处理方式，会导致相同符号的比较不会错误；而不同符号的比较时错误的
       * 
       * 应该这样处理
       * 
       * return (bits >= 0)? bits : (bits ^ 0x7fffffffffffffffL);
       * */
      long mask = -(bits >>> 63) | 0x8000000000000000L;
      return bits ^ mask;
    }
  }

  /**
   * Provides radix sort parameters. Comparators implementing this also are indicating that the
   * ordering they define is compatible with radix sort.
   */
  public abstract static class RadixSortSupport extends PrefixComparator {
    /** @return Whether the sort should be descending in binary sort order. */
    public abstract boolean sortDescending();

    /** @return Whether the sort should take into account the sign bit. */
    public abstract boolean sortSigned();
  }

  public static final  class RadixSortDemo extends PrefixComparators.RadixSortSupport{

    @Override
    public boolean sortDescending() {
      return false;
    }

    @Override
    public boolean sortSigned() {
      return false;
    }

    @Override
    public int compare(long prefix1, long prefix2) {
      return PrefixComparators.BINARY.compare(prefix1 & 0xffffff0000L, prefix1 & 0xffffff0000L);
    }
  }
  //
  // Standard prefix comparator implementations
  //

  public static final class UnsignedPrefixComparator extends RadixSortSupport {
    @Override public boolean sortDescending() { return false; }
    @Override public boolean sortSigned() { return false; }
    @Override
    public int compare(long aPrefix, long bPrefix) {
      return UnsignedLongs.compare(aPrefix, bPrefix);
    }
  }

  public static final class UnsignedPrefixComparatorDesc extends RadixSortSupport {
    @Override public boolean sortDescending() { return true; }
    @Override public boolean sortSigned() { return false; }
    @Override
    public int compare(long bPrefix, long aPrefix) {
      return UnsignedLongs.compare(aPrefix, bPrefix);
    }
  }

  public static final class SignedPrefixComparator extends RadixSortSupport {
    @Override public boolean sortDescending() { return false; }
    @Override public boolean sortSigned() { return true; }
    @Override
    public int compare(long a, long b) {
      return (a < b) ? -1 : (a > b) ? 1 : 0;
    }
  }

  public static final class SignedPrefixComparatorDesc extends RadixSortSupport {
    @Override public boolean sortDescending() { return true; }
    @Override public boolean sortSigned() { return true; }
    @Override
    public int compare(long b, long a) {
      return (a < b) ? -1 : (a > b) ? 1 : 0;
    }
  }
}
