package gesser.gals.editor;

import java.util.ArrayList;
import java.util.List;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.BadLocationException;

/**
 * Histórico de desfazer/refazer baseado em texto, um por documento.
 *
 * Substitui o UndoManager global original, que guardava os eventos estruturais
 * do DefaultStyledDocument. Aqui cada edição é registrada como
 * (inserção/remoção, posição, texto) e é desfeita reaplicando a operação inversa
 * pelo próprio documento, o que também refaz o realce de sintaxe.
 *
 * Digitação contínua é agrupada (uma palavra / uma linha por vez) e operações
 * compostas (substituir seleção, colar, setText) viram um único passo.
 */
public class EditHistory {
    private static final long GROUP_TIMEOUT_MS = 1000;
    private static final int LIMIT = 500;

    private static final class Op {
        final boolean insert;
        final int offset;
        final String text;

        Op(boolean insert, int offset, String text) {
            this.insert = insert;
            this.offset = offset;
            this.text = text;
        }
    }

    private static final class Group {
        final List<Op> ops = new ArrayList<Op>();
        long lastTime;
        boolean typing;
    }

    private final SyntaxDocument doc;
    private final List<Group> undoStack = new ArrayList<Group>();
    private final List<Group> redoStack = new ArrayList<Group>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private boolean replaying;
    private int compoundDepth;
    private Group compound;
    private boolean breakNext = true;

    EditHistory(SyntaxDocument doc) {
        this.doc = doc;
    }

    void recordInsert(int offset, String text) {
        record(new Op(true, offset, text));
    }

    void recordRemove(int offset, String text) {
        record(new Op(false, offset, text));
    }

    private void record(Op op) {
        if (replaying || op.text.length() == 0) {
            return;
        }
        redoStack.clear();
        long now = System.currentTimeMillis();

        if (compoundDepth > 0) {
            if (compound == null) {
                compound = new Group();
                push(compound);
            }
            compound.ops.add(op);
            compound.lastTime = now;
            fireChanged();
            return;
        }

        boolean typing = op.text.length() == 1;
        Group top = undoStack.isEmpty() ? null : undoStack.get(undoStack.size() - 1);
        if (!breakNext && typing && top != null && top.typing && now - top.lastTime < GROUP_TIMEOUT_MS
                && continues(top.ops.get(top.ops.size() - 1), op)) {
            top.ops.add(op);
            top.lastTime = now;
        } else {
            Group g = new Group();
            g.typing = typing;
            g.ops.add(op);
            g.lastTime = now;
            push(g);
        }
        // Fecha o grupo ao final de uma linha ou de uma palavra.
        char c = op.text.charAt(0);
        breakNext = !typing || c == '\n' || (op.insert && Character.isWhitespace(c));
        fireChanged();
    }

    private static boolean continues(Op last, Op op) {
        if (last.insert != op.insert) {
            return false;
        }
        if (op.insert) {
            return op.offset == last.offset + last.text.length();
        }
        // Backspace (apaga para trás) ou Delete (apaga para frente).
        return op.offset + op.text.length() == last.offset || op.offset == last.offset;
    }

    private void push(Group g) {
        undoStack.add(g);
        if (undoStack.size() > LIMIT) {
            undoStack.remove(0);
        }
    }

    /** Agrupa todas as edições até {@link #endCompound()} em um único passo. */
    public void beginCompound() {
        if (compoundDepth++ == 0) {
            compound = null;
        }
    }

    public void endCompound() {
        if (compoundDepth > 0 && --compoundDepth == 0) {
            compound = null;
            breakNext = true;
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Desfaz o último passo. Retorna a posição sugerida para o cursor, ou -1. */
    public int undo() {
        if (!canUndo()) {
            return -1;
        }
        Group g = undoStack.remove(undoStack.size() - 1);
        int caret = -1;
        replaying = true;
        try {
            for (int i = g.ops.size() - 1; i >= 0; i--) {
                Op op = g.ops.get(i);
                if (op.insert) {
                    doc.remove(op.offset, op.text.length());
                    caret = op.offset;
                } else {
                    doc.insertString(op.offset, op.text, null);
                    caret = op.offset + op.text.length();
                }
            }
        } catch (BadLocationException e) {
            // Histórico inconsistente com o texto: descarta para não corromper o documento.
            e.printStackTrace();
            clear();
            return -1;
        } finally {
            replaying = false;
            breakNext = true;
        }
        redoStack.add(g);
        fireChanged();
        return caret;
    }

    /** Refaz o último passo desfeito. Retorna a posição sugerida para o cursor, ou -1. */
    public int redo() {
        if (!canRedo()) {
            return -1;
        }
        Group g = redoStack.remove(redoStack.size() - 1);
        int caret = -1;
        replaying = true;
        try {
            for (Op op : g.ops) {
                if (op.insert) {
                    doc.insertString(op.offset, op.text, null);
                    caret = op.offset + op.text.length();
                } else {
                    doc.remove(op.offset, op.text.length());
                    caret = op.offset;
                }
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
            clear();
            return -1;
        } finally {
            replaying = false;
            breakNext = true;
        }
        undoStack.add(g);
        fireChanged();
        return caret;
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        breakNext = true;
        fireChanged();
    }

    public void addChangeListener(ChangeListener l) {
        listeners.add(l);
    }

    private void fireChanged() {
        ChangeEvent e = new ChangeEvent(this);
        for (ChangeListener l : new ArrayList<ChangeListener>(listeners)) {
            l.stateChanged(e);
        }
    }
}
