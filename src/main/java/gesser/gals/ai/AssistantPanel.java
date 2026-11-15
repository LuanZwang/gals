package gesser.gals.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import gesser.gals.Actions;
import gesser.gals.InputPane;
import gesser.gals.MainWindow;
import gesser.gals.generator.Options;
import gesser.gals.generator.OptionsDialog;
import gesser.gals.headless.GalsEngine;
import gesser.gals.util.GalsData;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Painel lateral com o assistente de IA (Claude) integrado ao editor do GALS. */
public class AssistantPanel extends JPanel implements GalsAssistant.Host {
    private static final String[] SECTION_NAMES = {"RegularDefinitions", "Tokens", "NonTerminals", "Grammar"};
    private static final File CONFIG = new File(System.getProperty("user.home"), ".gals/assistente.properties");

    private final JTextPane transcript = new JTextPane();
    private final JTextArea input = new JTextArea(4, 20);
    private final JButton send = new JButton("Enviar");
    private final JLabel status = new JLabel(" ");
    private GalsAssistant assistant;
    private boolean busy;

    private final SimpleAttributeSet userStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet aiStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet toolStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet errorStyle = new SimpleAttributeSet();

    public AssistantPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setPreferredSize(new Dimension(380, 400));

        StyleConstants.setBold(userStyle, true);
        StyleConstants.setForeground(userStyle, new Color(0, 70, 140));
        StyleConstants.setForeground(toolStyle, Color.GRAY);
        StyleConstants.setItalic(toolStyle, true);
        StyleConstants.setForeground(errorStyle, new Color(170, 0, 0));

        JPanel top = new JPanel(new BorderLayout());
        JLabel title = new JLabel(" Assistente (Claude)");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        top.add(title, BorderLayout.WEST);
        JButton reset = new JButton("Nova conversa");
        reset.setFocusable(false);
        reset.addActionListener(e -> newConversation());
        top.add(reset, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        transcript.setEditable(false);
        add(new JScrollPane(transcript), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        quick.add(quickButton("Verificar e explicar", "Verifique a especificação e explique os erros ou conflitos, se houver, sem editar nada."));
        quick.add(quickButton("Tornar LL(1)", "Deixe a gramática LL(1): remova recursão à esquerda, fatore e resolva conflitos, preservando as ações semânticas. Verifique no final."));
        quick.add(quickButton("Revisar", "Revise a especificação inteira (tokens, palavras reservadas, gramática) e aponte problemas e melhorias, sem editar."));
        bottom.add(quick, BorderLayout.NORTH);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        bottom.add(new JScrollPane(input), BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout());
        actions.add(status, BorderLayout.CENTER);
        actions.add(send, BorderLayout.EAST);
        bottom.add(actions, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        send.addActionListener(e -> submit(input.getText()));
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "gals-send");
        input.getActionMap().put("gals-send", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                submit(input.getText());
            }
        });
        append("Peça ajuda com a especificação aberta: explicar erros, criar tokens, tornar a gramática LL(1), "
                + "testar uma entrada... Ctrl+Enter envia. As edições do assistente podem ser desfeitas com Ctrl+Z.\n\n", toolStyle);
    }

    private JButton quickButton(String label, final String prompt) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(1, 4, 1, 4));
        b.addActionListener(e -> submit(prompt));
        return b;
    }

    private void newConversation() {
        if (busy) {
            return;
        }
        if (assistant != null) {
            assistant.reset();
        }
        transcript.setText("");
        status.setText(" ");
    }

    // ---------------------------------------------------------------- envio

    private void submit(final String text) {
        if (busy || text.trim().length() == 0) {
            return;
        }
        if (assistant == null) {
            AnthropicClient client = createClient(false);
            if (client == null) {
                return;
            }
            assistant = new GalsAssistant(client, this);
        }
        input.setText("");
        append("Você: ", userStyle);
        append(text.trim() + "\n\n", null);
        setBusy(true, "Pensando...");
        new SwingWorker<String, Void>() {
            protected String doInBackground() {
                return assistant.send(text.trim());
            }

            protected void done() {
                setBusy(false, " ");
                try {
                    append("Claude: ", userStyle);
                    append(get() + "\n\n", aiStyle);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    handleError(c, text);
                }
            }
        }.execute();
    }

    private void handleError(Throwable c, String text) {
        if (c instanceof UnauthorizedException) {
            append("Chave da API recusada (401).\n\n", errorStyle);
            AnthropicClient client = createClient(true);
            if (client != null) {
                assistant = new GalsAssistant(client, this);
                input.setText(text);
            }
        } else if (c instanceof RateLimitException) {
            append("Limite de uso da API atingido; tente de novo em instantes.\n\n", errorStyle);
            input.setText(text);
        } else if (c instanceof AnthropicServiceException) {
            append("Erro da API (" + ((AnthropicServiceException) c).statusCode() + "): " + c.getMessage() + "\n\n", errorStyle);
            input.setText(text);
        } else if (c instanceof AnthropicException) {
            append("Falha ao falar com a API: " + c.getMessage() + "\n\n", errorStyle);
            input.setText(text);
        } else {
            append("Erro: " + c + "\n\n", errorStyle);
            c.printStackTrace();
        }
    }

    private void setBusy(boolean b, String msg) {
        busy = b;
        send.setEnabled(!b);
        status.setText(msg);
    }

    private void append(String s, SimpleAttributeSet style) {
        StyledDocument doc = transcript.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), s, style);
        } catch (BadLocationException e) {
            // não acontece
        }
        transcript.setCaretPosition(doc.getLength());
    }

    // ------------------------------------------------------- credenciais

    /**
     * Cria o cliente: chave salva pelo GALS, senão ANTHROPIC_API_KEY / perfil do `ant auth login`.
     * Se nada estiver configurado (ou {@code ask}), pede a chave ao usuário.
     */
    private AnthropicClient createClient(boolean ask) {
        if (!ask) {
            String saved = loadKey();
            if (saved != null) {
                return AnthropicOkHttpClient.builder().apiKey(saved).build();
            }
            String env = System.getenv("ANTHROPIC_API_KEY");
            if (env != null && env.trim().length() > 0 || System.getenv("ANTHROPIC_AUTH_TOKEN") != null
                    || new File(System.getProperty("user.home"), ".config/anthropic").isDirectory()) {
                try {
                    return AnthropicOkHttpClient.fromEnv();
                } catch (RuntimeException e) {
                    // cai para pedir a chave
                }
            }
        }
        JPasswordField field = new JPasswordField(36);
        JCheckBox remember = new JCheckBox("Lembrar neste computador (" + CONFIG.getPath() + ")");
        Box box = Box.createVerticalBox();
        box.add(new JLabel("<html>O assistente usa a API do Claude (cobrada por uso).<br>"
                + "Informe uma chave da API Anthropic, ou defina ANTHROPIC_API_KEY antes de abrir o GALS.</html>"));
        box.add(Box.createVerticalStrut(6));
        box.add(field);
        box.add(remember);
        int r = JOptionPane.showConfirmDialog(this, box, "Chave da API Anthropic", JOptionPane.OK_CANCEL_OPTION);
        String key = new String(field.getPassword()).trim();
        if (r != JOptionPane.OK_OPTION || key.length() == 0) {
            return null;
        }
        if (remember.isSelected()) {
            saveKey(key);
        } else if (ask) {
            CONFIG.delete();
        }
        return AnthropicOkHttpClient.builder().apiKey(key).build();
    }

    private static String loadKey() {
        if (!CONFIG.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try {
            InputStream in = new FileInputStream(CONFIG);
            try {
                p.load(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
        String k = p.getProperty("apiKey");
        return k == null || k.trim().length() == 0 ? null : k.trim();
    }

    private static void saveKey(String key) {
        try {
            CONFIG.getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("apiKey", key);
            OutputStream out = new FileOutputStream(CONFIG);
            try {
                p.store(out, "Chave da API usada pelo assistente do GALS");
            } finally {
                out.close();
            }
            CONFIG.setReadable(false, false);
            CONFIG.setReadable(true, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------- Host (thread de fundo)

    /** Executa na thread do Swing e devolve o resultado (as ferramentas rodam numa thread de fundo). */
    private static <T> T onEdt(final Callable<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(task.call());
                } catch (Exception e) {
                    error.set(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get().getMessage(), error.get());
        }
        return result.get();
    }

    private static InputPane pane() {
        return MainWindow.getInstance().getInputPane();
    }

    private static GalsData snapshot() {
        return onEdt(() -> new GalsData(OptionsDialog.getInstance().getOptions(), pane().getData()));
    }

    private static int sectionIndex(String name) {
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            if (SECTION_NAMES[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("seção inválida: " + name);
    }

    public String readSpec() {
        return onEdt(() -> {
            Options o = OptionsDialog.getInstance().getOptions();
            StringBuilder sb = new StringBuilder("#Options\n").append(o.toString());
            for (int i = 0; i < SECTION_NAMES.length; i++) {
                boolean used = i == 1 || (i == 0 ? o.generateScanner : o.generateParser);
                sb.append("\n#").append(SECTION_NAMES[i]);
                if (!used) {
                    sb.append("   (seção desativada pelas opções)");
                }
                sb.append('\n');
                String[] lines = pane().sectionEditor(i).getText().replace("\r", "").split("\n", -1);
                for (int l = 0; l < lines.length; l++) {
                    sb.append(String.format("%4d| ", l + 1)).append(lines[l]).append('\n');
                }
            }
            return sb.toString();
        });
    }

    public String check() {
        return GalsEngine.checkReport(GalsEngine.analyze(snapshot()));
    }

    public String firstFollow() {
        return GalsEngine.firstFollow(GalsEngine.analyze(snapshot()));
    }

    public String simulate(String in) {
        return GalsEngine.simulate(GalsEngine.analyze(snapshot()), in);
    }

    public String editSection(final String section, final String oldText, final String newText) {
        final int idx = sectionIndex(section);
        String msg = onEdt(() -> {
            String text = pane().sectionEditor(idx).getDocument().getText(0, pane().sectionEditor(idx).getDocument().getLength());
            if (oldText.length() == 0) {
                String add = (text.length() > 0 && !text.endsWith("\n") ? "\n" : "") + newText;
                pane().replaceInSection(idx, text.length(), 0, add);
                return "Acrescentado ao final de " + section + ".";
            }
            int at = text.indexOf(oldText);
            if (at < 0) {
                throw new IllegalArgumentException("trecho_atual não encontrado em " + section
                        + ". Leia a especificação de novo e copie o trecho exato, sem os números de linha.");
            }
            if (text.indexOf(oldText, at + 1) >= 0) {
                throw new IllegalArgumentException("trecho_atual aparece mais de uma vez em " + section
                        + "; inclua mais contexto para ele ser único.");
            }
            pane().replaceInSection(idx, at, oldText.length(), newText);
            return "Editado em " + section + ".";
        });
        return msg + "\n\nVerificação após a edição:\n" + check();
    }

    public String replaceSection(final String section, final String text) {
        final int idx = sectionIndex(section);
        onEdt(() -> {
            int len = pane().sectionEditor(idx).getDocument().getLength();
            pane().replaceInSection(idx, 0, len, text);
            return null;
        });
        return "Seção " + section + " substituída.\n\nVerificação após a edição:\n" + check();
    }

    public String setOption(final String name, final String value) {
        return onEdt(() -> {
            OptionsDialog d = OptionsDialog.getInstance();
            Options o = d.getOptions();
            GalsEngine.applyOption(o, name, value);
            d.setOptions(o);
            MainWindow.getInstance().setPanesMode(d.getMode());
            MainWindow.getInstance().setChanged();
            Actions.setSaved(false);
            return "Opção " + name + " = " + value + ".";
        });
    }

    public void onToolCall(final String name, final Map<String, Object> in) {
        final String detail = in.containsKey("secao") ? " (" + in.get("secao") + ")"
                : in.containsKey("opcao") ? " (" + in.get("opcao") + " = " + in.get("valor") + ")" : "";
        SwingUtilities.invokeLater(() -> {
            append("→ " + name + detail + "\n", toolStyle);
            status.setText("Executando " + name + "...");
        });
    }

    public void onText(final String text) {
        SwingUtilities.invokeLater(() -> append(text + "\n", aiStyle));
    }
}
