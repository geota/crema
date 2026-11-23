// Generates the JVM vectors tests/jvm.rs checks de1-relay-app's Kotlin/JVM
// rules against: Double.toString, Float.toString (JDK 19+ shortest) and
// Kotlin's String.toDoubleOrNull() (its screening regex, then parseDouble).
//
//   java tests/vectors/jvm/JvmVectors.java tests/vectors/jvm   # from core/de1-relay-app, on JDK 21
//
// Seeded, so a rerun on the same JDK rewrites the same files.
import java.util.*;
public class JvmVectors {
  public static void main(String[] a) {
    Random r = new Random(4);
    StringBuilder d = new StringBuilder(), f = new StringBuilder(), p = new StringBuilder();
    long[] edge = {0L, 1L, 0x000FFFFFFFFFFFFFL, 0x0010000000000000L, 0x7FEFFFFFFFFFFFFFL, Double.doubleToLongBits(1e23), Double.doubleToLongBits(1e7), Double.doubleToLongBits(9999999.999999998), Double.doubleToLongBits(0.001), Double.doubleToLongBits(0.0009999999999999998), Double.doubleToLongBits(2e-3), Double.doubleToLongBits(100.0), Double.doubleToLongBits(1.2f), Double.doubleToLongBits(93.5), Double.doubleToLongBits(18.0), Double.doubleToLongBits(2.0E-323)};
    List<Long> ds = new ArrayList<>(); for (long e : edge) { ds.add(e); ds.add(e | 0x8000000000000000L); }
    for (int i = 0; i < 3000; i++) ds.add(r.nextLong());
    for (int i = 0; i < 1000; i++) { double v = r.nextInt(100000) / Math.pow(10, r.nextInt(12)); ds.add(Double.doubleToLongBits(v)); }
    for (long b : ds) { double v = Double.longBitsToDouble(b); if (Double.isNaN(v)) continue; d.append(String.format("%016x %s\n", b, Double.toString(v))); }
    List<Integer> fs = new ArrayList<>(List.of(0, 1, 0x007FFFFF, 0x00800000, 0x7F7FFFFF, Float.floatToIntBits(148f), Float.floatToIntBits(1.2f), Float.floatToIntBits(1e7f), Float.floatToIntBits(9999999f), Float.floatToIntBits(0.001f), Float.floatToIntBits(1e-3f*0.999f)));
    for (int i = 0; i < 3000; i++) fs.add(r.nextInt());
    for (int i = 0; i < 1000; i++) fs.add(Float.floatToIntBits(r.nextInt(100000) / (float)Math.pow(10, r.nextInt(8))));
    for (int b : fs) { float v = Float.intBitsToFloat(b); if (Float.isNaN(v)) continue; f.append(String.format("%08x %s\n", b, Float.toString(v))); f.append(String.format("%08x %s\n", b ^ 0x80000000, Float.toString(-v))); }
    String[] ins = {"", " ", "1", "-1", "+1", "1.", ".5", ".", "-.", "1e5", "1E+5", "1e-5", "1.e5", ".e5", "e5", "1e", "1e+", "18.0", " 18.0 ", "\t18\n", "\u000018\u0001", " 18", "18 ", "1d", "1D", "1f", "1F", "1.5f", "1df", "1e5d", "NaN", "-NaN", "+Infinity", "-Infinity", "Infinity", "infinity", "inf", "nan", "NaNd", "Infinityf", "0x1p3", "0X1P3", "0x1.8p1", "0x.8p1", "0x1p-1074", "0x1p-1075", "0x1.0000000000001p-1075", "0x1p-1076", "0x3p-1076", "0x1.fffffffffffff8p1023", "0x1.fffffffffffff7ffp1023", "0x1p1024", "0x1.00000000000008p0", "0x1.00000000000008000001p0", "0x1.00000000000018p0", "0x0.0000000000000000000000000001p120", "0x1p", "0xp1", "0x.p1", "0x1", "0x1p3f", "0x1p3d", "0x1fp0", "-0x1p0", "1e99999999999", "1e-99999999999", "00012.500", "1_000", "1,5", "0.1e1e1", "--1", "+-1", "2.2250738585072011e-308", "4.9e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "1.7976931348623157e308", "1.7976931348623159e308", "123456789012345678901234567890", "0.000000000000000000000000000000000000000000001", " \u001f1\u001f", "1 ", "1 2", "١"};
    for (String s : ins) {
      String out; try { out = Kt.ok(s) ? String.format("%016x", Double.doubleToRawLongBits(Double.parseDouble(s))) : "null"; } catch (NumberFormatException e) { out = "null"; }
      StringBuilder esc = new StringBuilder(); for (char c : s.toCharArray()) { if (c < 0x21 || c > 0x7e || c == '\\') esc.append(String.format("\\u{%x}", (int)c)); else esc.append(c); }
      p.append(esc).append('\t').append(out).append('\n');
    }
    try { java.nio.file.Files.writeString(java.nio.file.Path.of(a[0], "double_to_string.txt"), d); java.nio.file.Files.writeString(java.nio.file.Path.of(a[0], "float_to_string.txt"), f); java.nio.file.Files.writeString(java.nio.file.Path.of(a[0], "parse_double.txt"), p); } catch (Exception e) { throw new RuntimeException(e); }
  }
}
class Kt {
  // Kotlin's ScreenFloatValueRegEx (kotlin/text/StringNumberConversionsJVM.kt)
  static final java.util.regex.Pattern RE;
  static {
    String Digits = "(\\p{Digit}+)"; String HexDigits = "(\\p{XDigit}+)"; String Exp = "[eE][+-]?" + Digits;
    String HexString = "(0[xX]" + HexDigits + "(\\.)?)|" + "(0[xX]" + HexDigits + "?(\\.)" + HexDigits + ")";
    String Number = "(" + Digits + "(\\.)?(" + Digits + "?)(" + Exp + ")?)|" + "(\\.(" + Digits + ")(" + Exp + ")?)|" + "((" + HexString + ")[pP][+-]?" + Digits + ")";
    String fpRegex = "[\\x00-\\x20]*[+-]?(NaN|Infinity|((" + Number + ")[fFdD]?))[\\x00-\\x20]*";
    RE = java.util.regex.Pattern.compile(fpRegex);
  }
  static boolean ok(String s) { return RE.matcher(s).matches(); }
}
