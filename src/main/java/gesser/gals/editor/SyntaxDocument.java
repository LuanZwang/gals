package gesser.gals.editor;

import java.awt.Color;
import java.awt.Font;

import javax.swing.event.UndoableEditEvent;
import javax.swing.text.*;

public abstract class SyntaxDocument extends DefaultStyledDocument
{
	public static final Font FONT = new Font("Lucida Console",Font.PLAIN, 13);
	
	protected static final SimpleAttributeSet NORMAL = new SimpleAttributeSet();
	static
	{
		StyleConstants.setForeground(NORMAL, Color.BLACK);
		StyleConstants.setBackground(NORMAL, Color.WHITE);
		StyleConstants.setFontFamily(NORMAL, FONT.getFamily());
		StyleConstants.setFontSize(NORMAL, FONT.getSize());
		StyleConstants.setBold(NORMAL, false);
		StyleConstants.setItalic(NORMAL, false);
	}
	
	protected static final SimpleAttributeSet STRING = new SimpleAttributeSet(NORMAL);
	protected static final SimpleAttributeSet OPERATOR = new SimpleAttributeSet(NORMAL);
	protected static final SimpleAttributeSet REG_EXP = new SimpleAttributeSet(NORMAL);
	protected static final SimpleAttributeSet ERROR = new SimpleAttributeSet(NORMAL);
	protected static final SimpleAttributeSet COMMENT = new SimpleAttributeSet(NORMAL);

	
	private static void initAttributes()
	{	
		StyleConstants.setBackground(REG_EXP, Color.WHITE);
		StyleConstants.setForeground(REG_EXP, new Color(0, 128, 0));
		StyleConstants.setBold(REG_EXP, false);
		StyleConstants.setItalic(REG_EXP, false);
		
		StyleConstants.setBackground(STRING, Color.WHITE);
		StyleConstants.setForeground(STRING, Color.RED);
		StyleConstants.setBold(STRING, false);
		StyleConstants.setItalic(STRING, false);
		
		StyleConstants.setBackground(OPERATOR, Color.WHITE);
		StyleConstants.setForeground(OPERATOR, new Color(0, 0, 128));
		StyleConstants.setBold(OPERATOR, false);
		StyleConstants.setItalic(OPERATOR, false);
		/*
		StyleConstants.setBackground(ERROR, new Color(255, 32, 32));
		StyleConstants.setForeground(ERROR, Color.WHITE);
		StyleConstants.setBold(ERROR, true);
		StyleConstants.setItalic(ERROR, false);
		*/
		StyleConstants.setBackground(COMMENT, Color.WHITE);
		StyleConstants.setForeground(COMMENT, Color.DARK_GRAY);
		StyleConstants.setBold(COMMENT, false);
		StyleConstants.setItalic(COMMENT, true);
	}
	static  {initAttributes(); }
	
	protected abstract void apply(int startOffset, int endOffset, String input) throws BadLocationException;
	
	private final EditHistory history = new EditHistory(this);
	private boolean coloring;

	public EditHistory getHistory()
	{
		return history;
	}

	public void insertString(int offset, String str, AttributeSet a) throws BadLocationException 
	{
		if (str == null || str.length() == 0)
			return;

		super.insertString(offset, str, a);
		history.recordInsert(offset, str);
		refreshLines(offset, offset+str.length());
	}
	
	public void remove(int offset, int length) throws BadLocationException
	{
		if (length <= 0)
			return;

		String removed = getText(offset, length);
		super.remove(offset, length);
		history.recordRemove(offset, removed);
		refreshLines(offset, offset);
	}

	/** Substituir a seleção (digitar por cima, colar) é um único passo de desfazer. */
	public void replace(int offset, int length, String text, AttributeSet attrs) throws BadLocationException
	{
		history.beginCompound();
		try
		{
			super.replace(offset, length, text, attrs);
		}
		finally
		{
			history.endCompound();
		}
	}

	private void refreshLines(int start, int end) throws BadLocationException
	{
		int length = getLength();
		String text = getText(0, length);
		
		start--;
		while (start >= 0 && text.charAt(start) != '\n')
			start--;
		start++;

		if (end > length)
			end = length;
		while (end < length && text.charAt(end) != '\n')
			end++;

		refresh(start, end, text);
	}

	/**
	 * Aplica o realce sem gerar eventos de desfazer.
	 *
	 * A versão original removia os UndoableEditListeners durante o realce e os recolocava
	 * depois, sem try/finally: qualquer exceção em apply() deixava o editor sem listener,
	 * e o Ctrl+Z parava de funcionar até reiniciar o GALS.
	 */
	private void refresh(int start, int end, String text)
	{
		coloring = true;
		try
		{
			apply(start, end, text);
		}
		catch (BadLocationException e)
		{
			e.printStackTrace();
		}
		catch (RuntimeException e)
		{
			// um erro no realce nunca deve impedir a edição do texto
			e.printStackTrace();
		}
		finally
		{
			coloring = false;
		}
	}

	protected void fireUndoableEditUpdate(UndoableEditEvent e)
	{
		if (!coloring)
			super.fireUndoableEditUpdate(e);
	}
}
