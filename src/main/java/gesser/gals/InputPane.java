package gesser.gals;

import gesser.gals.analyser.AnalysisError;
import gesser.gals.editor.BNFDocument;
import gesser.gals.editor.DefinitionsDocument;
import gesser.gals.editor.EditHistory;
import gesser.gals.editor.NTDocument;
import gesser.gals.editor.SyntaxDocument;
import gesser.gals.editor.TokensDocument;
import gesser.gals.generator.OptionsDialog;
import gesser.gals.generator.parser.Grammar;
import gesser.gals.generator.scanner.FiniteAutomata;
import gesser.gals.parserparser.Parser;
import gesser.gals.scannerparser.LineParser;
import gesser.gals.util.MetaException;

import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.Document;
import javax.swing.text.StyledEditorKit;

/**
 * @author Gesser
 */

public class InputPane extends JPanel implements MouseListener, UndoableEditListener
{
	public static final int LEXICAL  = 0;
	public static final int SYNTATIC = 1;
	public static final int BOTH     = 2;
	
	private JEditorPane grammar      = new JEditorPane();
	private JEditorPane nonTerminals = new JEditorPane();
	private JEditorPane tokens       = new JEditorPane();
	private JEditorPane definitions  = new JEditorPane();
	
	/** Editor que recebeu foco por último; alvo de Desfazer/Refazer da barra e do menu. */
	private JEditorPane activeEditor = grammar;
	
	private JPanel base = new JPanel(new BorderLayout());
	private JList errorList = new JList();
	
	private JPanel pnlGrammar 		= createPanel(" Gramática", grammar, new BNFDocument());
	private JPanel pnlNonTerminals = createPanel(" Não Terminais", nonTerminals, new NTDocument());
	private JPanel pnlTokens 		= createPanel(" Tokens", tokens, new TokensDocument());
	private JPanel pnlDefinitions  = createPanel(" Definições Regulares", definitions, new DefinitionsDocument());
	
	private int mode;
	private JSplitPane mainSplit;
	
	public InputPane()
	{
		super (new BorderLayout());
		
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, base, new JScrollPane(errorList));
		mainSplit = split;
		split.setResizeWeight(0.9);
		add(split);
		
		setMode(BOTH);
		
		split.setDividerLocation(355);
		
		errorList.addMouseListener(this);
	}
	
	public Grammar getGrammar() throws MetaException
	{
		errorList.setListData(new Object[]{});
		
		if (mode != SYNTATIC && mode != BOTH)
			return null;
		
		List tokens = getTokens();
		List nt = new ArrayList();
		
		StringTokenizer ntTok = new StringTokenizer(nonTerminals.getText(), "\n", true);		
		while (ntTok.hasMoreTokens())
			nt.add(ntTok.nextToken());
		
		String gram = grammar.getText();
		
		
		Grammar g = new Parser().parse(tokens, nt, gram);
		return g;		
	}
	
	public FiniteAutomata getFiniteAutomata() throws MetaException
	{
		errorList.setListData(new Object[]{});
		
		if (mode != LEXICAL && mode != BOTH)
			return null;
		
		LineParser lp = new LineParser();
						
		return lp.parseFA(definitions.getText(), tokens.getText());
	}
	
	public List getTokens() throws MetaException
	{
		List result = new ArrayList();
		if (mode == BOTH || mode == LEXICAL)
		{
			List tokens = getFiniteAutomata().getTokens();
			for (int i=0; i<tokens.size(); i++)
			{
				result.add(tokens.get(i));
				result.add("\n");
			}
		}
		else //mode == SYNTATIC
		{
			StringTokenizer tknzr = new StringTokenizer(tokens.getText(), "\n", true);
			
			while (tknzr.hasMoreTokens())
				result.add(tknzr.nextToken());
		}
		return result;
	}
	
	public Data getData()
	{
		boolean lex = mode == LEXICAL || mode == BOTH;
		boolean synt = mode == SYNTATIC || mode == BOTH;
		
		return 
			new Data(
				lex ? definitions.getText() : "", 
				tokens.getText(),
				synt ? nonTerminals.getText() : "", 
				synt ? grammar.getText() : "");
	}
	
	public void setData(Data d)
	{
		setTextAsOneEdit(definitions, d.getDefinitions());
		setTextAsOneEdit(tokens, d.getTokens());
		setTextAsOneEdit(nonTerminals, d.getNonTerminals());
		setTextAsOneEdit(grammar, d.getGrammar());
		
		definitions.setCaretPosition(0);
		tokens.setCaretPosition(0);
		nonTerminals.setCaretPosition(0);
		grammar.setCaretPosition(0);
		
		MainWindow.getInstance().setChanged();
	}
	
	public void reset()
	{
		grammar.setText("");
		nonTerminals.setText("");
		tokens.setText("");
		definitions.setText("");
		clearHistory();
		OptionsDialog.getInstance().reset();
		MainWindow.getInstance().setChanged();
	}
	
	public void setMode(int mode)
	{
		this.mode = mode;
		
		switch (mode)
		{
			case LEXICAL:  setLex(); break;
			case SYNTATIC: setSynt(); break;
			case BOTH:     setBoth(); break;
		}
	}
	
	private void setBoth()
	{
		base.removeAll();
		
		JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pnlDefinitions, pnlTokens);
		
		top.setResizeWeight(0.25);
		
		JSplitPane bottom = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pnlNonTerminals, pnlGrammar);
		
		bottom.setResizeWeight(0.15);
		
		JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
		
		main.setResizeWeight(0.5);
		
		base.add(main);
		
		validate();
		repaint();
		
		top.setDividerLocation(0.25);
		bottom.setDividerLocation(0.15);
		main.setDividerLocation(0.5);
	}

	private void setSynt()
	{
		base.removeAll();
		
		JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pnlTokens, pnlNonTerminals);
		
		left.setResizeWeight(0.5);
		
		JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, pnlGrammar);
		
		main.setResizeWeight(0.15);
		
		base.add(main);
		
		validate();
		repaint();
		
		left.setDividerLocation(0.5);
		main.setDividerLocation(0.15);
	}

	
	private void setLex()
	{
		base.removeAll();
		
		JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pnlDefinitions, pnlTokens);
		
		main.setResizeWeight(0.25);
		
		base.add(main);
		
		validate();
		repaint();
		
		main.setDividerLocation(0.25);
	}
	
	public void undoableEditHappened(UndoableEditEvent e)
	{
		// O desfazer é feito pelo EditHistory de cada documento;
		// este listener só marca o arquivo como alterado.
		Actions.setSaved(false);
		MainWindow.getInstance().setChanged();
	}
	
	public JEditorPane getActiveEditor()
	{
		return activeEditor;
	}
	
	private static EditHistory historyOf(JEditorPane pane)
	{
		return ((SyntaxDocument)pane.getDocument()).getHistory();
	}
	
	public boolean canUndo()
	{
		return historyOf(activeEditor).canUndo();
	}
	
	public boolean canRedo()
	{
		return historyOf(activeEditor).canRedo();
	}
	
	public void undo()
	{
		undo(activeEditor);
	}
	
	public void redo()
	{
		redo(activeEditor);
	}
	
	private static void undo(JEditorPane pane)
	{
		moveCaret(pane, historyOf(pane).undo());
	}
	
	private static void redo(JEditorPane pane)
	{
		moveCaret(pane, historyOf(pane).redo());
	}
	
	private static void moveCaret(JEditorPane pane, int caret)
	{
		if (caret < 0)
			Toolkit.getDefaultToolkit().beep();
		else
			pane.setCaretPosition(Math.min(caret, pane.getDocument().getLength()));
	}
	
	/** Apaga o histórico de desfazer de todos os editores (novo arquivo / arquivo aberto). */
	public void clearHistory()
	{
		JEditorPane[] panes = {definitions, tokens, nonTerminals, grammar};
		for (int i=0; i<panes.length; i++)
			historyOf(panes[i]).clear();
		Actions.updateUndoState();
	}
	
	/** Reposiciona a lista de erros (~15% da altura) depois que a janela muda de tamanho. */
	public void fitErrorList()
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			public void run()
			{
				mainSplit.setDividerLocation(0.85);
			}
		});
	}
	
	/** Editor de uma seção: 0 definições regulares, 1 tokens, 2 não-terminais, 3 gramática. */
	public JEditorPane sectionEditor(int section)
	{
		switch (section)
		{
			case 0:  return definitions;
			case 1:  return tokens;
			case 2:  return nonTerminals;
			default: return grammar;
		}
	}
	
	/**
	 * Substitui um trecho de uma seção como um único passo de desfazer (usado pelo assistente de IA).
	 * Seleciona e rola até o trecho alterado para o usuário ver a mudança.
	 */
	public void replaceInSection(int section, int offset, int length, String text) throws javax.swing.text.BadLocationException
	{
		JEditorPane pane = sectionEditor(section);
		SyntaxDocument doc = (SyntaxDocument)pane.getDocument();
		EditHistory h = doc.getHistory();
		h.beginCompound();
		try
		{
			doc.replace(offset, length, text, null);
		}
		finally
		{
			h.endCompound();
		}
		int end = Math.min(offset + text.length(), doc.getLength());
		pane.select(offset, end);
		try
		{
			java.awt.Rectangle r = pane.modelToView(offset);
			if (r != null)
				pane.scrollRectToVisible(r);
		}
		catch (javax.swing.text.BadLocationException e)
		{
			// só rolagem
		}
		MainWindow.getInstance().setChanged();
	}
	
	/** Troca o texto de um editor como um único passo de desfazer. */
	private static void setTextAsOneEdit(JEditorPane pane, String text)
	{
		EditHistory h = historyOf(pane);
		h.beginCompound();
		try
		{
			pane.setText(text);
		}
		finally
		{
			h.endCompound();
		}
	}
				
	private JPanel createPanel(String caption, final JEditorPane comp, Document doc)
	{
		JPanel pnl = new JPanel(new BorderLayout());
		
		comp.setEditorKit(new StyledEditorKit());
		comp.setDocument(doc);
		
		comp.getDocument().addUndoableEditListener(this);
		((SyntaxDocument)doc).getHistory().addChangeListener(new ChangeListener()
		{
			public void stateChanged(ChangeEvent e)
			{
				Actions.updateUndoState();
			}
		});
		comp.addFocusListener(new FocusAdapter()
		{
			public void focusGained(FocusEvent e)
			{
				activeEditor = comp;
				Actions.updateUndoState();
			}
		});
		
		// Atalhos só deste editor. A versão original os colocava no Keymap padrão do Swing,
		// que é compartilhado por todos os campos de texto (inclusive o do simulador).
		InputMap im = comp.getInputMap(JComponent.WHEN_FOCUSED);
		ActionMap am = comp.getActionMap();
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "gals-undo");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "gals-redo");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "gals-redo");
		am.put("gals-undo", new AbstractAction()
		{
			public void actionPerformed(ActionEvent e)
			{
				undo(comp);
			}
		});
		am.put("gals-redo", new AbstractAction()
		{
			public void actionPerformed(ActionEvent e)
			{
				redo(comp);
			}
		});
					
		pnl.add(new JLabel(caption), BorderLayout.NORTH);
		
		JPanel tmp = new JPanel(new BorderLayout());
		tmp.add(comp);
		
		JScrollPane scroll = new JScrollPane(tmp);
		scroll.getVerticalScrollBar().setUnitIncrement(10);
		scroll.getHorizontalScrollBar().setUnitIncrement(10);
		pnl.add(scroll);
		
		return pnl;
	}
	
	
	private static class ErrorData
	{
		int index, position, mode;
		String message;
		
		ErrorData(String message, int index, int position, int mode)
		{
			this.message = message;
			this.index = index;
			this.position = position;
			this.mode = mode;
		}
		
		public String toString()
		{
			return message /*+ ", linha: "+index+", coluna "+position*/;
		}
	}
	public void handleError(MetaException e)
	{			
		AnalysisError ae = (AnalysisError) e.getCause();
		int line = e.getIndex();
		String msg = "";
		switch (e.getMode())
		{
			case MetaException.DEFINITION :
				msg = "Erro em Definição Regular: ";
				break;
			case MetaException.TOKEN :
				msg = "Erro na Especificação de Tokens: ";
				break;
			case MetaException.NON_TERMINAL :
				msg = "Erro na Declaração dos Não-Terminais: ";
				break;
			case MetaException.GRAMMAR :
				msg = "Erro na Especificação da Gramática: ";
				break;
		}
		msg += ae.getMessage();
		
		ErrorData ed = new ErrorData(msg, line, ae.getPosition(), e.getMode());
		
		ErrorData[] errors = {ed};
		
		errorList.setListData(errors);
		e.printStackTrace();
		Toolkit.getDefaultToolkit().beep();
		mouseClicked(null);
	}
	
	public void mouseEntered(MouseEvent e) {}
	public void mouseExited(MouseEvent e) {}
	public void mousePressed(MouseEvent e) {}
	public void mouseReleased(MouseEvent e) {}

	public void mouseClicked(MouseEvent e)
	{
		ErrorData error = (ErrorData) errorList.getSelectedValue();

		if (error != null)
		{
			switch (error.mode)
			{
				case MetaException.DEFINITION:
					setPosition(definitions, error.index, error.position);
					definitions.requestFocus();
					break;
				case MetaException.TOKEN :
					setPosition(tokens, error.index, error.position);
					tokens.requestFocus();
					break;
				case MetaException.NON_TERMINAL :
					setPosition(nonTerminals, error.index, error.position);
					nonTerminals.requestFocus();
					break;
				case MetaException.GRAMMAR :
					grammar.getCaret().setDot(error.position);
					grammar.requestFocus();
					break;
			}
		}
	}
	
	private void setPosition(JEditorPane pane, int line, int col )
	{
		String text = pane.getText();
		int pos = 0;
		int strpos = 0;
		while (line>0)
		{
			while (strpos < text.length() && text.charAt(strpos) != '\n')
			{				
				if (text.charAt(strpos) != '\r')
					pos++;
				strpos++;				
			}
			strpos++;
			pos++;
			line--;
		}
		pos += col;
		pane.setCaretPosition(pos);
	}

	public static class Data
	{
		private String definitions = "";
		private String tokens = "";
		private String nonTerminals = "";
		private String grammar = "";
		
		public Data(String definitions, String tokens, String nonTerminals, String grammar)
		{
			this.definitions = definitions;
			this.tokens = tokens;
			this.nonTerminals = nonTerminals;
			this.grammar = grammar;
		}
		
		public String getDefinitions()
		{
			return definitions;
		}

		public String getGrammar()
		{
			return grammar;
		}

		public String getNonTerminals()
		{
			return nonTerminals;
		}

		public String getTokens()
		{
			return tokens;
		}
		
		public void setGrammar(String string)
		{
			this.grammar = string;
		}

		public void setNonTerminals(String string)
		{
			this.nonTerminals = string;
		}
	}
}
