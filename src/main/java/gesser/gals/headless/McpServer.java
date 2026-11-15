package gesser.gals.headless;

import gesser.gals.util.GalsData;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Servidor MCP (Model Context Protocol) do GALS via stdio, JSON-RPC 2.0 com uma mensagem por linha.
 * Permite que o Claude Code (ou outro cliente MCP) verifique, simule e gere código de arquivos .gals.
 *
 * Registro no Claude Code:  claude mcp add gals -- java -jar /caminho/gals.jar mcp
 */
public final class McpServer {
    private static final String VERSION = "2003.10-furb.1";
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    private final PrintWriter out;

    private McpServer(PrintWriter out) {
        this.out = out;
    }

    public static void run() throws IOException {
        // stdout é exclusivo do protocolo: qualquer print do GALS vai para stderr.
        PrintStream protocol = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        System.setOut(System.err);
        McpServer server = new McpServer(new PrintWriter(new OutputStreamWriter(protocol, "UTF-8"), true));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().length() == 0) {
                continue;
            }
            server.handle(line);
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(String line) {
        Object id = null;
        try {
            Map<String, Object> msg = (Map<String, Object>) Json.parse(line);
            id = msg.get("id");
            String method = (String) msg.get("method");
            Map<String, Object> params = msg.get("params") instanceof Map ? (Map<String, Object>) msg.get("params") : null;
            if (method == null || id == null && method.startsWith("notifications/")) {
                return;
            }
            Object result;
            if (method.equals("initialize")) {
                Object pv = params == null ? null : params.get("protocolVersion");
                result = Json.obj(
                        "protocolVersion", pv != null ? pv : DEFAULT_PROTOCOL,
                        "capabilities", Json.obj("tools", Json.obj()),
                        "serverInfo", Json.obj("name", "gals", "version", VERSION),
                        "instructions", "Ferramentas do GALS (gerador de analisadores léxicos e sintáticos). "
                                + "Use gals_reference para a sintaxe .gals, gals_check após editar um .gals, "
                                + "gals_simulate para testar entradas e gals_generate para gerar Lexico/Sintatico/Semantico.");
            } else if (method.equals("ping")) {
                result = Json.obj();
            } else if (method.equals("tools/list")) {
                result = Json.obj("tools", tools());
            } else if (method.equals("tools/call")) {
                result = call((String) params.get("name"),
                        params.get("arguments") instanceof Map ? (Map<String, Object>) params.get("arguments") : Json.obj());
            } else {
                if (id != null) {
                    send(Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", -32601L, "message", "Método não suportado: " + method)));
                }
                return;
            }
            if (id != null) {
                send(Json.obj("jsonrpc", "2.0", "id", id, "result", result));
            }
        } catch (Exception e) {
            if (id != null) {
                send(Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", -32603L, "message", String.valueOf(e))));
            }
        }
    }

    private void send(Object msg) {
        out.println(Json.write(msg));
        out.flush();
    }

    // ------------------------------------------------------------------ tools

    private static Map<String, Object> specProps() {
        return Json.obj(
                "file", Json.obj("type", "string", "description", "Caminho do arquivo .gals"),
                "content", Json.obj("type", "string", "description", "Conteúdo .gals inline (alternativa a 'file')"));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<Object> required) {
        Map<String, Object> schema = Json.obj("type", "object", "properties", props);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return Json.obj("name", name, "description", description, "inputSchema", schema);
    }

    private static List<Object> tools() {
        List<Object> l = new ArrayList<Object>();
        l.add(tool("gals_reference",
                "Guia da sintaxe de arquivos .gals (opções, definições regulares, tokens, não-terminais, gramática, regras LL(1)).",
                Json.obj(), null));
        l.add(tool("gals_check",
                "Verifica uma especificação .gals como o botão 'Verificar Erros' do GALS, e também checa se a gramática é LL(1) "
                        + "(ou conflitos LR). Retorna erros com seção, linha e coluna, e os conflitos encontrados.",
                specProps(), null));
        l.add(tool("gals_info",
                "Lista as opções, os tokens (com o id numérico usado no código gerado) e as produções numeradas da especificação.",
                specProps(), null));
        l.add(tool("gals_first_follow", "Conjuntos FIRST e FOLLOW de cada não-terminal.", specProps(), null));
        Map<String, Object> sim = specProps();
        sim.put("input", Json.obj("type", "string", "description", "Programa de entrada a analisar"));
        l.add(tool("gals_simulate",
                "Roda o léxico e o sintático gerados sobre uma entrada, como o Simulador do GALS: lista os tokens "
                        + "e mostra a árvore de derivação com as ações semânticas, dizendo se a entrada foi aceita.",
                sim, Json.arr("input")));
        Map<String, Object> gen = specProps();
        gen.put("output_dir", Json.obj("type", "string", "description", "Pasta onde gravar os arquivos gerados"));
        gen.put("options", Json.obj("type", "object", "description",
                "Sobrescreve opções do .gals, ex.: {\"Parser\":\"SLR\",\"Language\":\"Java\",\"Package\":\"x.y\",\"ScannerTable\":\"Compact\"}",
                "additionalProperties", Json.obj("type", "string")));
        gen.put("skip", Json.obj("type", "array", "items", Json.obj("type", "string"),
                "description", "Arquivos que NÃO devem ser gravados, ex.: [\"Semantico.java\"] para preservar a implementação existente"));
        gen.put("dry_run", Json.obj("type", "boolean", "description", "Se true, só lista o que seria gerado, sem gravar"));
        gen.put("show", Json.obj("type", "string", "description", "Nome de um arquivo gerado para retornar o conteúdo (ex.: \"Constants.java\")"));
        l.add(tool("gals_generate",
                "Gera os fontes do analisador (Lexico, Sintatico, Semantico, Constants, ...) como o 'Gerar Código' do GALS. "
                        + "Cuidado: sobrescreve arquivos existentes em output_dir, exceto os listados em 'skip'.",
                gen, null));
        return l;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(String name, Map<String, Object> args) {
        try {
            if ("gals_reference".equals(name)) {
                return text(GalsReference.TEXT, false);
            }
            GalsData data = load(args);
            if ("gals_generate".equals(name) && args.get("options") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) args.get("options")).entrySet()) {
                    GalsEngine.applyOption(data.getOptions(), e.getKey(), String.valueOf(e.getValue()));
                }
            }
            GalsEngine.Analysis a = GalsEngine.analyze(data);
            if ("gals_check".equals(name)) {
                return text(GalsEngine.checkReport(a), false);
            } else if ("gals_info".equals(name)) {
                return text(GalsEngine.infoReport(a), false);
            } else if ("gals_first_follow".equals(name)) {
                return text(GalsEngine.firstFollow(a), !a.ok());
            } else if ("gals_simulate".equals(name)) {
                return text(GalsEngine.simulate(a, (String) args.get("input")), !a.ok());
            } else if ("gals_generate".equals(name)) {
                if (!a.ok()) {
                    return text("Nada foi gerado.\n\n" + GalsEngine.checkReport(a), true);
                }
                Map<String, String> files = GalsEngine.generate(a);
                StringBuilder sb = new StringBuilder();
                Object show = args.get("show");
                boolean dry = Boolean.TRUE.equals(args.get("dry_run"));
                List<String> skip = new ArrayList<String>();
                if (args.get("skip") instanceof List) {
                    for (Object o : (List<Object>) args.get("skip")) {
                        skip.add(String.valueOf(o));
                    }
                }
                if (!dry && args.get("output_dir") != null) {
                    List<String> written = GalsEngine.write(files, new File((String) args.get("output_dir")), skip);
                    sb.append("Gravados ").append(written.size()).append(" arquivo(s):\n");
                    for (String w : written) {
                        sb.append("  ").append(w).append('\n');
                    }
                    if (!skip.isEmpty()) {
                        sb.append("Preservados (skip): ").append(skip).append('\n');
                    }
                } else {
                    sb.append(dry ? "Simulação (nada gravado). " : "Nenhuma output_dir informada; nada gravado. ")
                      .append("Arquivos que seriam gerados:\n");
                    for (Map.Entry<String, String> e : files.entrySet()) {
                        sb.append("  ").append(e.getKey()).append(" (").append(e.getValue().length()).append(" caracteres)\n");
                    }
                }
                if (show != null) {
                    String body = files.get(String.valueOf(show));
                    sb.append("\n--- ").append(show).append(" ---\n").append(body == null ? "(arquivo não gerado)" : body);
                }
                if (!a.warnings.isEmpty()) {
                    sb.append('\n').append(GalsEngine.checkReport(a));
                }
                return text(sb.toString(), false);
            }
            return text("Ferramenta desconhecida: " + name, true);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return text("Erro: " + e.getMessage() + "\n\n" + sw, true);
        }
    }

    private static GalsData load(Map<String, Object> args) throws IOException {
        Object content = args.get("content");
        if (content != null) {
            return GalsEngine.parse(String.valueOf(content));
        }
        Object file = args.get("file");
        if (file == null) {
            throw new IllegalArgumentException("Informe 'file' (caminho do .gals) ou 'content'.");
        }
        File f = new File(String.valueOf(file));
        if (!f.isFile()) {
            throw new IOException("Arquivo não encontrado: " + f.getAbsolutePath());
        }
        return GalsEngine.load(f);
    }

    private static Map<String, Object> text(String s, boolean isError) {
        return Json.obj("content", Json.arr(Json.obj("type", "text", "text", s)), "isError", Boolean.valueOf(isError));
    }
}
