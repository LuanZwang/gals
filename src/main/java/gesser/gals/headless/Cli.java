package gesser.gals.headless;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Linha de comando do GALS (sem janela). */
public final class Cli {
    private Cli() {
    }

    private static final String USAGE =
        "Uso: java -jar gals.jar <comando> [argumentos]\n"
      + "\n"
      + "  (sem argumentos)                          abre a interface gráfica\n"
      + "  check <arquivo.gals>                      verifica erros e conflitos LL/LR\n"
      + "  info <arquivo.gals>                       opções, tokens e produções numeradas\n"
      + "  first-follow <arquivo.gals>               conjuntos FIRST e FOLLOW\n"
      + "  simulate <arquivo.gals> <entrada.txt>     simula léxico + sintático (use - para ler do stdin)\n"
      + "  generate <arquivo.gals> <pasta> [--Opcao=Valor ...] [--skip=Arquivo.java,...]\n"
      + "                                            gera os fontes (ex.: --Parser=SLR --Package=x.y)\n"
      + "  reference                                 guia da sintaxe .gals\n"
      + "  mcp                                       servidor MCP via stdio (para o Claude Code)\n";

    /** Retorna o código de saída: 0 ok, 1 erros na especificação, 2 uso incorreto. */
    public static int run(String[] args) throws Exception {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        String cmd = args[0];
        if (cmd.equals("mcp")) {
            McpServer.run();
            return 0;
        }
        if (cmd.equals("reference")) {
            out.print(GalsReference.TEXT);
            return 0;
        }
        if (cmd.equals("-h") || cmd.equals("--help") || cmd.equals("help") || args.length < 2) {
            out.print(USAGE);
            return cmd.startsWith("-h") || cmd.equals("--help") || cmd.equals("help") ? 0 : 2;
        }
        File spec = new File(args[1]);
        gesser.gals.util.GalsData data = GalsEngine.load(spec);
        List<String> skip = new ArrayList<String>();
        List<String> rest = new ArrayList<String>();
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--skip=")) {
                for (String s : a.substring(7).split(",")) {
                    if (s.trim().length() > 0) {
                        skip.add(s.trim());
                    }
                }
            } else if (a.startsWith("--") && a.indexOf('=') > 2) {
                GalsEngine.applyOption(data.getOptions(), a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            } else {
                rest.add(a);
            }
        }
        GalsEngine.Analysis an = GalsEngine.analyze(data);
        if (cmd.equals("check")) {
            out.println(GalsEngine.checkReport(an));
            return an.ok() ? 0 : 1;
        } else if (cmd.equals("info")) {
            out.println(GalsEngine.infoReport(an));
            return an.ok() ? 0 : 1;
        } else if (cmd.equals("first-follow")) {
            out.println(GalsEngine.firstFollow(an));
            return an.ok() ? 0 : 1;
        } else if (cmd.equals("simulate")) {
            if (rest.isEmpty()) {
                out.print(USAGE);
                return 2;
            }
            String input = rest.get(0).equals("-") ? readAll(System.in) : readAll(new FileInputStream(rest.get(0)));
            String report = GalsEngine.simulate(an, input);
            out.println(report);
            return an.ok() && report.contains("ACEITA") || report.contains("aceita pelo léxico") ? 0 : 1;
        } else if (cmd.equals("generate")) {
            if (rest.isEmpty()) {
                out.print(USAGE);
                return 2;
            }
            if (!an.ok()) {
                out.println("Nada foi gerado.\n\n" + GalsEngine.checkReport(an));
                return 1;
            }
            Map<String, String> files = GalsEngine.generate(an);
            for (String w : GalsEngine.write(files, new File(rest.get(0)), skip)) {
                out.println("gerado: " + w);
            }
            for (String s : skip) {
                out.println("preservado: " + s);
            }
            for (String w : an.warnings) {
                out.println("aviso: " + w);
            }
            return 0;
        }
        out.print(USAGE);
        return 2;
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
