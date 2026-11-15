package gesser.gals.gas;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodifica tabelas inteiras escritas como literais "{{1,2},{3,4}}".
 * As tabelas de Constants foram movidas para strings porque, como
 * inicializadores de array, ultrapassam o limite de 64KB de bytecode
 * por método imposto pelo javac atual.
 */
final class ArrayLiteral {
    private final String s;
    private int pos;

    private ArrayLiteral(String s) {
        this.s = s;
    }

    static int[][] decode2(String literal) {
        Object[] rows = (Object[]) new ArrayLiteral(literal).parse();
        int[][] result = new int[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            result[i] = toInts((Object[]) rows[i]);
        }
        return result;
    }

    static int[][][] decode3(String literal) {
        Object[] planes = (Object[]) new ArrayLiteral(literal).parse();
        int[][][] result = new int[planes.length][][];
        for (int i = 0; i < planes.length; i++) {
            Object[] rows = (Object[]) planes[i];
            result[i] = new int[rows.length][];
            for (int j = 0; j < rows.length; j++) {
                result[i][j] = toInts((Object[]) rows[j]);
            }
        }
        return result;
    }

    private static int[] toInts(Object[] values) {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = ((Integer) values[i]).intValue();
        }
        return result;
    }

    private Object parse() {
        if (s.charAt(pos) == '{') {
            pos++;
            List<Object> items = new ArrayList<Object>();
            if (s.charAt(pos) == '}') {
                pos++;
                return items.toArray();
            }
            while (true) {
                items.add(parse());
                char c = s.charAt(pos++);
                if (c == '}') {
                    return items.toArray();
                }
                if (c != ',') {
                    throw new IllegalStateException("Literal inválido na posição " + (pos - 1));
                }
            }
        }
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        while (Character.isDigit(s.charAt(pos))) {
            pos++;
        }
        return Integer.valueOf(s.substring(start, pos));
    }
}
