package gesser.gals.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import gesser.gals.headless.GalsReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assistente de IA do GALS: conversa com o Claude e deixa o modelo ler, verificar, simular e
 * editar a especificação aberta por meio de ferramentas (laço manual de tool use).
 * Não depende de Swing: a interface implementa {@link Host}.
 */
public final class GalsAssistant {
    public static final String MODEL = "claude-opus-5-5";
    private static final int MAX_STEPS = 30;

    /** O que o assistente pode fazer no GALS. Cada método devolve o texto do resultado da ferramenta. */
    public interface Host {
        String readSpec();

        String check();

        String firstFollow();

        String simulate(String input);

        /** Troca a primeira ocorrência exata de oldText por newText na seção. */
        String editSection(String section, String oldText, String newText);

        String replaceSection(String section, String text);

        String setOption(String name, String value);

        /** Chamado antes de cada ferramenta, para mostrar o progresso. */
        void onToolCall(String name, Map<String, Object> input);

        /** Texto que o modelo escreveu antes de chamar ferramentas. */
        void onText(String text);
    }

    private static final String SYSTEM =
        "Você é o assistente integrado ao GALS, o gerador de analisadores léxicos e sintáticos usado na disciplina "
      + "de Compiladores. O usuário edita a especificação em quatro editores (Definições Regulares, Tokens, "
      + "Não Terminais, Gramática) mais as Opções, e você tem ferramentas para ler, verificar, simular e editar "
      + "exatamente o que está aberto na janela.\n\n"
      + "Como trabalhar:\n"
      + "- Leia a especificação (ler_especificacao) antes de opinar ou editar; não suponha o conteúdo.\n"
      + "- Prefira editar_secao com trechos pequenos e exatos; use substituir_secao só para reescritas grandes.\n"
      + "- Depois de editar, rode verificar e corrija o que quebrar. Para mostrar que uma linguagem é aceita, use simular.\n"
      + "- Preserve as ações semânticas #n: ao transformar a gramática (remover recursão à esquerda, fatorar), "
      + "mantenha cada #n na mesma posição relativa aos símbolos, pois o Semantico do aluno depende disso.\n"
      + "- Cada edição sua vira um passo de Desfazer (Ctrl+Z) no editor correspondente; o usuário pode desfazer.\n"
      + "- Se o usuário só fizer uma pergunta, responda sem editar.\n"
      + "- Responda em português do Brasil, de forma direta. Ao terminar edições, diga o que mudou e por quê, "
      + "citando as produções/tokens afetados.\n\n"
      + GalsReference.TEXT;

    private final AnthropicClient client;
    private final Host host;
    private final List<MessageParam> history = new ArrayList<MessageParam>();
    private final List<Tool> tools;

    public GalsAssistant(AnthropicClient client, Host host) {
        this.client = client;
        this.host = host;
        this.tools = buildTools();
    }

    public void reset() {
        history.clear();
    }

    /** Envia uma mensagem do usuário e executa o laço de ferramentas. Devolve a resposta final em texto. */
    public String send(String userText) {
        int mark = history.size();
        try {
            return loop(userText);
        } catch (RuntimeException e) {
            // Não deixa a conversa com um tool_use sem resposta: volta ao estado anterior à mensagem.
            while (history.size() > mark) {
                history.remove(history.size() - 1);
            }
            throw e;
        }
    }

    private String loop(String userText) {
        history.add(MessageParam.ofUser(com.anthropic.models.messages.UserMessageParam.builder().content(userText).build()));
        StringBuilder finalText = new StringBuilder();
        for (int step = 0; step < MAX_STEPS; step++) {
            MessageCreateParams.Builder b = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(16000L)
                    .system(SYSTEM)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .messages(history)
                    // Recusa por classificador de segurança cai para outro modelo automaticamente.
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            for (Tool t : tools) {
                b.addTool(t);
            }
            Message response = client.messages().create(b.build());
            history.add(response.toParam());

            StopReason stop = response.stopReason().orElse(null);
            List<ToolUseBlock> calls = new ArrayList<ToolUseBlock>();
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : response.content()) {
                if (block.text().isPresent()) {
                    text.append(block.text().get().text());
                }
                if (block.toolUse().isPresent()) {
                    calls.add(block.toolUse().get());
                }
            }

            if (StopReason.REFUSAL.equals(stop)) {
                return "O modelo recusou este pedido" + response.stopDetails()
                        .map(d -> ": " + d.explanation().orElse("")).orElse(".");
            }
            if (calls.isEmpty() || !StopReason.TOOL_USE.equals(stop)) {
                finalText.append(text);
                if (StopReason.MAX_TOKENS.equals(stop)) {
                    finalText.append("\n\n(Resposta cortada pelo limite de tokens.)");
                }
                return finalText.toString().trim();
            }
            if (text.length() > 0) {
                host.onText(text.toString().trim());
            }

            List<ContentBlockParam> results = new ArrayList<ContentBlockParam>();
            for (ToolUseBlock call : calls) {
                String out;
                boolean error = false;
                try {
                    Map<String, Object> input = toMap(call._input());
                    host.onToolCall(call.name(), input);
                    out = run(call.name(), input);
                } catch (RuntimeException e) {
                    out = "Erro ao executar " + call.name() + ": " + e.getMessage();
                    error = true;
                }
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id())
                        .content(out)
                        .isError(error)
                        .build()));
            }
            history.add(MessageParam.ofUserOfContentBlockParams(results));
        }
        return "Parei após " + MAX_STEPS + " passos de ferramentas sem concluir. Peça para continuar se quiser.";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonValue v) {
        Map<String, Object> m = v.convert(Map.class);
        return m == null ? new LinkedHashMap<String, Object>() : m;
    }

    private static String str(Map<String, Object> in, String key) {
        Object v = in.get(key);
        if (v == null) {
            throw new IllegalArgumentException("parâmetro obrigatório ausente: " + key);
        }
        return String.valueOf(v);
    }

    private String run(String name, Map<String, Object> in) {
        if (name.equals("ler_especificacao")) {
            return host.readSpec();
        } else if (name.equals("verificar")) {
            return host.check();
        } else if (name.equals("first_follow")) {
            return host.firstFollow();
        } else if (name.equals("simular")) {
            return host.simulate(str(in, "entrada"));
        } else if (name.equals("editar_secao")) {
            return host.editSection(str(in, "secao"), str(in, "trecho_atual"), str(in, "trecho_novo"));
        } else if (name.equals("substituir_secao")) {
            return host.replaceSection(str(in, "secao"), str(in, "texto"));
        } else if (name.equals("definir_opcao")) {
            return host.setOption(str(in, "opcao"), str(in, "valor"));
        }
        throw new IllegalArgumentException("ferramenta desconhecida");
    }

    // ------------------------------------------------------------- ferramentas

    private static final List<String> SECTIONS = Arrays.asList("RegularDefinitions", "Tokens", "NonTerminals", "Grammar");

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> sectionProp() {
        Map<String, Object> m = prop("string", "Seção da especificação");
        m.put("enum", SECTIONS);
        return m;
    }

    private static Tool tool(String name, String description, Map<String, Map<String, Object>> props) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        List<String> required = new ArrayList<String>();
        for (Map.Entry<String, Map<String, Object>> e : props.entrySet()) {
            pb.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
            required.add(e.getKey());
        }
        return Tool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(pb.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static List<Tool> buildTools() {
        List<Tool> l = new ArrayList<Tool>();
        Map<String, Map<String, Object>> none = new LinkedHashMap<String, Map<String, Object>>();
        l.add(tool("ler_especificacao",
                "Lê a especificação aberta no GALS: opções e o texto das quatro seções, com números de linha.", none));
        l.add(tool("verificar",
                "Verifica a especificação aberta (como 'Verificar Erros') e checa LL(1) ou conflitos LR conforme o parser escolhido. "
                        + "Retorna erros com seção, linha e coluna.", none));
        l.add(tool("first_follow", "Conjuntos FIRST e FOLLOW de cada não-terminal da gramática aberta.", none));

        Map<String, Map<String, Object>> sim = new LinkedHashMap<String, Map<String, Object>>();
        sim.put("entrada", prop("string", "Programa de entrada a analisar"));
        l.add(tool("simular",
                "Roda o léxico e o sintático sobre uma entrada, listando os tokens e a árvore de derivação, e diz se foi aceita.", sim));

        Map<String, Map<String, Object>> ed = new LinkedHashMap<String, Map<String, Object>>();
        ed.put("secao", sectionProp());
        ed.put("trecho_atual", prop("string", "Trecho exato que existe hoje na seção (copie do ler_especificacao, sem os números de linha). Vazio para acrescentar ao final."));
        ed.put("trecho_novo", prop("string", "Texto que substitui o trecho"));
        l.add(tool("editar_secao",
                "Substitui um trecho exato de uma seção no editor do GALS (um passo de Ctrl+Z). Falha se o trecho não for encontrado.", ed));

        Map<String, Map<String, Object>> rep = new LinkedHashMap<String, Map<String, Object>>();
        rep.put("secao", sectionProp());
        rep.put("texto", prop("string", "Novo conteúdo completo da seção"));
        l.add(tool("substituir_secao",
                "Reescreve uma seção inteira no editor do GALS (um passo de Ctrl+Z). Use só para mudanças grandes.", rep));

        Map<String, Map<String, Object>> opt = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, Object> name = prop("string", "Opção a alterar");
        name.put("enum", Arrays.asList("Parser", "Language", "ScannerTable", "Input", "Package", "ScannerCaseSensitive"));
        opt.put("opcao", name);
        opt.put("valor", prop("string", "Novo valor, ex.: Parser = LL, RD, SLR, LALR ou LR; Language = Java, C++ ou Delphi"));
        l.add(tool("definir_opcao", "Altera uma opção do projeto (como no diálogo Opções).", opt));
        return l;
    }
}
