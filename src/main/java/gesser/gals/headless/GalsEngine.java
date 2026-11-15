package gesser.gals.headless;

import gesser.gals.InputPane;
import gesser.gals.analyser.AnalysisError;
import gesser.gals.analyser.Token;
import gesser.gals.generator.Options;
import gesser.gals.generator.cpp.CppCommomGenerator;
import gesser.gals.generator.cpp.CppParserGenerator;
import gesser.gals.generator.cpp.CppScannerGeneretor;
import gesser.gals.generator.delphi.DelphiCommomGenerator;
import gesser.gals.generator.delphi.DelphiParserGenerator;
import gesser.gals.generator.delphi.DelphiScannerGenerator;
import gesser.gals.generator.java.JavaCommonGenerator;
import gesser.gals.generator.java.JavaParserGenerator;
import gesser.gals.generator.java.JavaScannerGenerator;
import gesser.gals.generator.parser.ConflictSolver;
import gesser.gals.generator.parser.Grammar;
import gesser.gals.generator.parser.ll.LLConflictSolver;
import gesser.gals.generator.parser.ll.LLParser;
import gesser.gals.generator.parser.ll.NotLLException;
import gesser.gals.generator.parser.lr.Command;
import gesser.gals.generator.parser.lr.LRConflictSolver;
import gesser.gals.generator.parser.lr.LRGenerator;
import gesser.gals.generator.parser.lr.LRGeneratorFactory;
import gesser.gals.generator.scanner.FiniteAutomata;
import gesser.gals.generator.scanner.LexicalData;
import gesser.gals.parserparser.Parser;
import gesser.gals.scannerparser.LineParser;
import gesser.gals.simulator.FiniteAutomataSimulator;
import gesser.gals.simulator.LL1ParserSimulator;
import gesser.gals.simulator.LRParserSimulator;
import gesser.gals.util.GalsData;
import gesser.gals.util.MetaException;
import gesser.gals.util.XMLProcessor;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;
import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Núcleo do GALS sem interface gráfica: verificar, analisar, simular e gerar código
 * a partir de uma especificação .gals. Usado pela linha de comando, pelo servidor MCP
 * e pelo assistente de IA.
 */
public final class GalsEngine {
    public static final String[] SECTIONS = {"RegularDefinitions", "Tokens", "NonTerminals", "Grammar"};
    public static final String[] LANGUAGES = {"Java", "C++", "Delphi"};
    public static final String[] PARSERS = {"LR", "LALR", "SLR", "LL", "RD"};
    public static final String[] SCANNER_TABLES = {"Full", "Compact", "Hardcode"};

    private GalsEngine() {
    }

    // ------------------------------------------------------------------ modelo

    /** Um erro na especificação, com seção e linha/coluna (1-based). */
    public static final class Diagnostic {
        public final String section;
        public final int line;
        public final int column;
        public final String message;

        Diagnostic(String section, int line, int column, String message) {
            this.section = section;
            this.line = line;
            this.column = column;
            this.message = message;
        }

        public String toString() {
            return section + (line > 0 ? " (linha " + line + ", coluna " + column + ")" : "") + ": " + message;
        }
    }

    /** Resultado da análise de uma especificação. */
    public static final class Analysis {
        public Options options;
        public InputPane.Data data;
        public FiniteAutomata automata;
        public Grammar grammar;
        public List<String> tokenNames = new ArrayList<String>();
        public final List<Diagnostic> errors = new ArrayList<Diagnostic>();
        public final List<String> warnings = new ArrayList<String>();
        public final List<String> conflicts = new ArrayList<String>();

        public boolean lexical() {
            return options.generateScanner;
        }

        public boolean syntactic() {
            return options.generateParser;
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    // --------------------------------------------------------------- carregar

    public static GalsData load(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return XMLProcessor.load(in);
        } catch (gesser.gals.util.XMLParsingException e) {
            throw new IOException("Arquivo .gals inválido: " + e.getMessage());
        } finally {
            in.close();
        }
    }

    public static GalsData parse(String content) throws IOException {
        try {
            return XMLProcessor.load(new ByteArrayInputStream(content.getBytes("UTF-8")));
        } catch (gesser.gals.util.XMLParsingException e) {
            throw new IOException("Especificação .gals inválida: " + e.getMessage());
        }
    }

    /** Texto .gals (formato novo, com seções #) para os dados e opções. */
    public static String toGalsText(Options options, InputPane.Data data) {
        return new GalsData(options, data).toString();
    }

    // ---------------------------------------------------------------- analisar

    /** Constrói o autômato e a gramática, coletando erros e conflitos sem abrir diálogos. */
    public static Analysis analyze(GalsData gd) {
        Analysis a = new Analysis();
        a.options = gd.getOptions() != null ? gd.getOptions() : new Options();
        a.data = gd.getData();
        Options previous = currentOrNull();
        Options.setCurrent(a.options);
        RecordingSolver solver = new RecordingSolver(a.conflicts);
        ConflictSolver.setDelegate(solver);
        try {
            buildAutomata(a);
            if (a.ok()) {
                buildGrammar(a);
            }
            if (a.ok() && a.grammar != null) {
                checkParser(a);
            }
        } finally {
            ConflictSolver.setDelegate(null);
            Options.setCurrent(previous);
        }
        return a;
    }

    private static Options currentOrNull() {
        return Options.peekCurrent();
    }

    private static void buildAutomata(Analysis a) {
        try {
            if (a.lexical()) {
                a.automata = new LineParser().parseFA(a.data.getDefinitions(), a.data.getTokens());
                for (Object t : a.automata.getTokens()) {
                    a.tokenNames.add((String) t);
                }
            } else {
                StringTokenizer tk = new StringTokenizer(a.data.getTokens(), "\n");
                while (tk.hasMoreTokens()) {
                    String t = tk.nextToken().trim();
                    if (t.length() > 0) {
                        a.tokenNames.add(t);
                    }
                }
            }
        } catch (MetaException e) {
            a.errors.add(diagnostic(e, a.data));
        } catch (RuntimeException e) {
            a.errors.add(new Diagnostic("Tokens", 0, 0, "Erro interno ao montar o autômato: " + e));
        }
    }

    private static void buildGrammar(Analysis a) {
        if (!a.syntactic()) {
            return;
        }
        try {
            List tokens = new ArrayList();
            if (a.lexical()) {
                for (String t : a.tokenNames) {
                    tokens.add(t);
                    tokens.add("\n");
                }
            } else {
                StringTokenizer tk = new StringTokenizer(a.data.getTokens(), "\n", true);
                while (tk.hasMoreTokens()) {
                    tokens.add(tk.nextToken());
                }
            }
            List nt = new ArrayList();
            StringTokenizer ntTok = new StringTokenizer(a.data.getNonTerminals(), "\n", true);
            while (ntTok.hasMoreTokens()) {
                nt.add(ntTok.nextToken());
            }
            a.grammar = new Parser().parse(tokens, nt, a.data.getGrammar());
        } catch (MetaException e) {
            a.errors.add(diagnostic(e, a.data));
        } catch (RuntimeException e) {
            a.errors.add(new Diagnostic("Grammar", 0, 0, "Erro interno ao montar a gramática: " + e));
        }
    }

    private static void checkParser(Analysis a) {
        Grammar g = a.grammar;
        int p = a.options.parser;
        try {
            if (p == Options.PARSER_LL || p == Options.PARSER_REC_DESC) {
                if (g.hasLeftRecursion()) {
                    int s = g.getLeftRecursiveSimbol();
                    a.errors.add(new Diagnostic("Grammar", 0, 0, "Gramática não é LL(1): recursão à esquerda"
                            + (s >= 0 ? " em " + g.getSymbols()[s] : "")));
                    return;
                }
                if (!g.isFactored()) {
                    StringBuffer sb = new StringBuffer("Gramática não é LL(1): não está fatorada. Produções com prefixo comum:");
                    BitSet nf = g.getNonFactoratedProductions();
                    for (int i = nf.nextSetBit(0); i >= 0; i = nf.nextSetBit(i + 1)) {
                        sb.append("\n  ").append(g.getProductions().getProd(i));
                    }
                    a.errors.add(new Diagnostic("Grammar", 0, 0, sb.toString()));
                    return;
                }
                new LLParser(g).generateTable();
                if (!a.conflicts.isEmpty()) {
                    a.errors.add(new Diagnostic("Grammar", 0, 0, "Gramática não é LL(1): " + a.conflicts.size()
                            + " conflito(s) na tabela (terceira condição LL(1)). O GALS resolveria cada um escolhendo a primeira produção."));
                }
            } else {
                LRGenerator gen = LRGeneratorFactory.createGenerator(g);
                gen.buildIntTable();
                if (!a.conflicts.isEmpty()) {
                    a.warnings.add(a.conflicts.size() + " conflito(s) na tabela " + PARSERS[p]
                            + ". O GALS resolveria cada um escolhendo a primeira ação.");
                }
            }
        } catch (NotLLException e) {
            a.errors.add(new Diagnostic("Grammar", 0, 0, "Gramática não é LL(1): " + e.getMessage()));
        } catch (RuntimeException e) {
            a.errors.add(new Diagnostic("Grammar", 0, 0, "Erro interno ao montar a tabela sintática: " + e));
        }
    }

    /** Converte a exceção do GALS em seção + linha/coluna 1-based. */
    static Diagnostic diagnostic(MetaException e, InputPane.Data data) {
        AnalysisError ae = (AnalysisError) e.getCause();
        String msg = ae == null ? String.valueOf(e.getMessage()) : ae.getMessage();
        int pos = ae == null ? 0 : ae.getPosition();
        switch (e.getMode()) {
            case MetaException.DEFINITION:
                return new Diagnostic("RegularDefinitions", e.getIndex() + 1, pos + 1, msg);
            case MetaException.TOKEN:
                return new Diagnostic("Tokens", e.getIndex() + 1, pos + 1, msg);
            case MetaException.NON_TERMINAL:
                return new Diagnostic("NonTerminals", e.getIndex() + 1, pos + 1, msg);
            default: {
                String text = data.getGrammar();
                int line = 1;
                int col = 1;
                for (int i = 0; i < pos && i < text.length(); i++) {
                    if (text.charAt(i) == '\n') {
                        line++;
                        col = 1;
                    } else if (text.charAt(i) != '\r') {
                        col++;
                    }
                }
                return new Diagnostic("Grammar", line, col, msg);
            }
        }
    }

    /** Registra conflitos LL/LR em vez de perguntar ao usuário; escolhe a primeira opção, como o diálogo. */
    static final class RecordingSolver implements LLConflictSolver, LRConflictSolver {
        private final List<String> out;

        RecordingSolver(List<String> out) {
            this.out = out;
        }

        public int resolve(Grammar g, BitSet conflict, int input, int stackTop) {
            String in = input == 0 ? "$" : g.getTerminals()[input - 1];
            StringBuffer sb = new StringBuffer();
            sb.append("Conflito LL(1): topo da pilha ").append(g.getNonTerminals()[stackTop])
              .append(", entrada ").append(in).append(". Produções possíveis:");
            for (int i = conflict.nextSetBit(0); i >= 0; i = conflict.nextSetBit(i + 1)) {
                sb.append("\n    [").append(i).append("] ").append(g.getProductions().getProd(i));
            }
            out.add(sb.toString());
            return conflict.nextSetBit(0);
        }

        public int resolve(Grammar g, Command[] conflict, int state, int input) {
            String in = input == 0 ? "$" : g.getTerminals()[input - 1];
            StringBuffer sb = new StringBuffer();
            sb.append("Conflito LR: estado ").append(state).append(", entrada ").append(in).append(". Ações possíveis:");
            for (int i = 0; i < conflict.length; i++) {
                Command c = conflict[i];
                sb.append("\n    ");
                switch (c.getType()) {
                    case Command.SHIFT:
                        sb.append("empilhar e ir para o estado ").append(c.getParameter());
                        break;
                    case Command.REDUCE:
                        sb.append("reduzir pela produção ").append(c.getParameter()).append(": ")
                          .append(g.getProductions().getProd(c.getParameter()));
                        break;
                    case Command.ACTION:
                        sb.append("ação semântica #").append(c.getParameter());
                        break;
                    default:
                        sb.append(c);
                }
            }
            out.add(sb.toString());
            return 0;
        }
    }

    // ------------------------------------------------------------- relatórios

    public static String checkReport(Analysis a) {
        StringBuffer sb = new StringBuffer();
        sb.append("Modo: ").append(a.lexical() && a.syntactic() ? "léxico + sintático" : a.lexical() ? "só léxico" : "só sintático")
          .append(" | Linguagem: ").append(LANGUAGES[a.options.language])
          .append(" | Parser: ").append(PARSERS[a.options.parser]).append('\n');
        if (a.ok()) {
            sb.append("Nenhum erro encontrado.\n");
        } else {
            sb.append(a.errors.size()).append(" erro(s):\n");
            for (Diagnostic d : a.errors) {
                sb.append("- ").append(d).append('\n');
            }
        }
        for (String w : a.warnings) {
            sb.append("Aviso: ").append(w).append('\n');
        }
        if (!a.conflicts.isEmpty()) {
            sb.append("\nConflitos:\n");
            for (String c : a.conflicts) {
                sb.append("- ").append(c).append('\n');
            }
        }
        if (a.ok()) {
            sb.append('\n').append(a.tokenNames.size()).append(" token(s)");
            if (a.grammar != null) {
                sb.append(", ").append(a.grammar.getNonTerminals().length).append(" não-terminal(is), ")
                  .append(a.grammar.getProductions().size()).append(" produção(ões)");
            }
            sb.append('.');
        }
        return sb.toString();
    }

    public static String infoReport(Analysis a) {
        StringBuffer sb = new StringBuffer();
        sb.append("Opções:\n").append(a.options.toString()).append('\n');
        sb.append("Tokens (").append(a.tokenNames.size()).append("):\n");
        for (int i = 0; i < a.tokenNames.size(); i++) {
            sb.append("  ").append(i + 2).append(": ").append(a.tokenNames.get(i)).append('\n');
        }
        if (a.grammar != null) {
            sb.append("\nProduções (numeradas como no GALS):\n");
            for (int i = 0; i < a.grammar.getProductions().size(); i++) {
                sb.append("  ").append(i).append(": ").append(a.grammar.getProductions().getProd(i)).append('\n');
            }
            sb.append("\nSímbolo inicial: ").append(a.grammar.getSymbols()[a.grammar.getStartSymbol()]).append('\n');
        }
        if (!a.ok()) {
            sb.append('\n').append(checkReport(a));
        }
        return sb.toString();
    }

    public static String firstFollow(Analysis a) {
        if (!a.ok()) {
            return checkReport(a);
        }
        if (a.grammar == null) {
            return "A especificação não tem parte sintática (GenerateParser = false).";
        }
        return a.grammar.stringFirstFollow();
    }

    // ---------------------------------------------------------------- simular

    /** Executa o léxico e (se houver) o sintático sobre uma entrada, como no Simulador do GALS. */
    public static String simulate(Analysis a, String input) {
        if (!a.ok()) {
            return "A especificação tem erros; corrija antes de simular.\n\n" + checkReport(a);
        }
        Options previous = currentOrNull();
        Options.setCurrent(a.options);
        ConflictSolver.setDelegate(new RecordingSolver(new ArrayList<String>()));
        try {
            FiniteAutomata fa = a.automata != null ? a.automata : tokenListAutomata(a.tokenNames);
            StringBuffer sb = new StringBuffer();
            sb.append("== Análise léxica ==\n");
            FiniteAutomataSimulator sim = new FiniteAutomataSimulator(fa);
            sim.setInput(input);
            boolean lexOk = true;
            try {
                for (Token t = sim.nextToken(); t != null; t = sim.nextToken()) {
                    String name = t.getId() - 2 < a.tokenNames.size() ? a.tokenNames.get(t.getId() - 2) : "#" + t.getId();
                    sb.append(pad(name, 16)).append(' ').append(pad("\"" + t.getLexeme() + "\"", 20))
                      .append(" ").append(where(input, t.getPosition())).append('\n');
                }
            } catch (gesser.gals.analyser.LexicalError e) {
                lexOk = false;
                sb.append("ERRO LÉXICO ").append(where(input, e.getPosition())).append(": ").append(e.getMessage()).append('\n');
            }
            if (a.grammar != null) {
                sb.append("\n== Análise sintática (").append(PARSERS[a.options.parser]).append(") ==\n");
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("Derivação");
                FiniteAutomataSimulator scanner = new FiniteAutomataSimulator(fa);
                scanner.setInput(input);
                String error = null;
                try {
                    if (a.options.parser == Options.PARSER_LL || a.options.parser == Options.PARSER_REC_DESC) {
                        new LL1ParserSimulator(new LLParser(a.grammar)).parse(scanner, root);
                    } else {
                        new LRParserSimulator(LRGeneratorFactory.createGenerator(a.grammar)).parse(scanner, root);
                    }
                } catch (AnalysisError e) {
                    error = e.getMessage() + " " + where(input, e.getPosition());
                } catch (NotLLException e) {
                    error = "Gramática não é LL(1): " + e.getMessage();
                }
                boolean treeError = appendTree(sb, root, 0);
                if (error != null) {
                    sb.append("ERRO: ").append(error).append('\n');
                }
                sb.append(error == null && !treeError && lexOk ? "\nEntrada ACEITA.\n" : "\nEntrada REJEITADA.\n");
            } else {
                sb.append(lexOk ? "\nEntrada aceita pelo léxico.\n" : "\nEntrada rejeitada pelo léxico.\n");
            }
            return sb.toString();
        } finally {
            ConflictSolver.setDelegate(null);
            Options.setCurrent(previous);
        }
    }

    private static FiniteAutomata tokenListAutomata(List<String> names) {
        try {
            LexicalData ld = new LexicalData();
            for (String t : names) {
                ld.addToken(t, t);
            }
            ld.addIgnore("[\\ \\n\\r\\t]");
            return ld.getFA();
        } catch (MetaException e) {
            throw new IllegalStateException("Não foi possível montar o autômato da lista de tokens", e);
        }
    }

    private static boolean appendTree(StringBuffer sb, DefaultMutableTreeNode node, int depth) {
        boolean error = false;
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        String s = String.valueOf(node.getUserObject());
        sb.append(s).append('\n');
        if (s.startsWith("ERRO") || s.contains("Erro") || s.contains("esperado")) {
            error = true;
        }
        Enumeration children = node.children();
        while (children.hasMoreElements()) {
            error |= appendTree(sb, (DefaultMutableTreeNode) children.nextElement(), depth + 1);
        }
        return error;
    }

    private static String where(String input, int pos) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < pos && i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return "(linha " + line + ", coluna " + col + ")";
    }

    private static String pad(String s, int n) {
        StringBuffer sb = new StringBuffer(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ gerar

    /**
     * Gera os fontes (como "Gerar Código" do GALS). Retorna nome do arquivo → conteúdo, com
     * quebras de linha "\n". Lança IllegalStateException se a especificação tem erros.
     */
    public static Map<String, String> generate(Analysis a) throws NotLLException {
        if (!a.ok()) {
            throw new IllegalStateException("A especificação tem erros:\n" + checkReport(a));
        }
        Options o = a.options;
        Options previous = currentOrNull();
        Options.setCurrent(o);
        ConflictSolver.setDelegate(new RecordingSolver(new ArrayList<String>()));
        try {
            TreeMap all = new TreeMap();
            FiniteAutomata fa = a.automata;
            Grammar g = a.grammar;
            switch (o.language) {
                case Options.LANG_JAVA:
                    all.putAll(new JavaCommonGenerator().generate(fa, g, o));
                    all.putAll(new JavaScannerGenerator().generate(fa, o));
                    all.putAll(new JavaParserGenerator().generate(g, o));
                    break;
                case Options.LANG_CPP:
                    all.putAll(new CppCommomGenerator().generate(fa, g, o));
                    all.putAll(new CppScannerGeneretor().generate(fa, o));
                    all.putAll(new CppParserGenerator().generate(g, o));
                    break;
                default:
                    all.putAll(new DelphiCommomGenerator().generate(fa, g, o));
                    all.putAll(new DelphiScannerGenerator().generate(fa, o));
                    all.putAll(new DelphiParserGenerator().generate(g, o));
            }
            Map<String, String> result = new TreeMap<String, String>();
            for (Object k : all.keySet()) {
                result.put((String) k, (String) all.get(k));
            }
            return result;
        } finally {
            ConflictSolver.setDelegate(null);
            Options.setCurrent(previous);
        }
    }

    /**
     * Grava os arquivos gerados em {@code dir} com a quebra de linha do sistema, como o GALS.
     * Arquivos listados em {@code skip} não são gravados (ex.: preservar o Semantico.java já implementado).
     */
    public static List<String> write(Map<String, String> files, File dir, List<String> skip) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta " + dir);
        }
        String lb = System.getProperty("line.separator");
        List<String> written = new ArrayList<String>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (skip != null && skip.contains(e.getKey())) {
                continue;
            }
            File f = new File(dir, e.getKey());
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), System.getProperty("gals.encoding", "UTF-8")));
            try {
                w.write(e.getValue().replace("\n", lb));
            } finally {
                w.close();
            }
            written.add(f.getPath());
        }
        return written;
    }

    // ------------------------------------------------------------------ opções

    /** Aplica sobrescritas de opções no formato do .gals (ex.: "Parser" → "SLR"). */
    public static void applyOption(Options o, String key, String value) {
        String k = key.trim().toLowerCase();
        String v = value.trim();
        if (k.equals("language")) {
            o.language = indexOf(LANGUAGES, v, "Language");
        } else if (k.equals("parser")) {
            o.parser = indexOf(PARSERS, v, "Parser");
        } else if (k.equals("scannertable")) {
            o.scannerTable = indexOf(SCANNER_TABLES, v, "ScannerTable");
        } else if (k.equals("input")) {
            o.input = v.equalsIgnoreCase("Stream") ? Options.INPUT_STREAM : Options.INPUT_STRING;
        } else if (k.equals("package")) {
            o.pkgName = v;
        } else if (k.equals("scannername")) {
            o.scannerName = v;
        } else if (k.equals("parsername")) {
            o.parserName = v;
        } else if (k.equals("semanticname")) {
            o.semanticName = v;
        } else if (k.equals("scannercasesensitive")) {
            o.scannerCaseSensitive = Boolean.valueOf(v).booleanValue();
        } else {
            throw new IllegalArgumentException("Opção desconhecida: " + key);
        }
    }

    private static int indexOf(String[] values, String v, String name) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(v)) {
                return i;
            }
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < values.length; i++) {
            sb.append(i > 0 ? ", " : "").append(values[i]);
        }
        throw new IllegalArgumentException(name + " inválido: " + v + " (use " + sb + ")");
    }
}
