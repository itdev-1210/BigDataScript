package org.bds.lang.expression;

import java.util.List;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bds.Config;
import org.bds.compile.BdsNodeWalker;
import org.bds.compile.CompilerMessage.MessageType;
import org.bds.compile.CompilerMessages;
import org.bds.lang.BdsNode;
import org.bds.lang.statement.Block;
import org.bds.lang.statement.Statement;
import org.bds.lang.statement.StatementExpr;
import org.bds.lang.type.Type;
import org.bds.lang.type.Types;
import org.bds.lang.value.InterpolateVars;
import org.bds.lang.value.Literal;
import org.bds.lang.value.LiteralString;
import org.bds.symbol.SymbolTable;
import org.bds.util.Gpr;
import org.bds.util.GprString;

/**
 * A 'task' expression
 *
 * @author pcingola
 */
public class ExpressionTask extends ExpressionWithScope {

	private static final long serialVersionUID = 5026042355679287158L;

	// Variable names
	public static final String TASK_OPTION_CPUS = "cpus";
	public static final String TASK_OPTION_MEM = "mem";
	public static final String TASK_OPTION_CAN_FAIL = "canFail";
	public static final String TASK_OPTION_ALLOW_EMPTY = "allowEmpty";
	public static final String TASK_OPTION_NODE = "node";
	public static final String TASK_OPTION_PHYSICAL_PATH = "ppwd";
	public static final String TASK_OPTION_QUEUE = "queue";
	public static final String TASK_OPTION_RETRY = "retry";
	public static final String TASK_OPTION_SYSTEM = "system";
	public static final String TASK_OPTION_TASKNAME = "taskName";
	public static final String TASK_OPTION_TIMEOUT = "timeout";
	public static final String TASK_OPTION_WALL_TIMEOUT = "walltimeout";

	public static final String CMD_DOWNLOAD = "bds -download";
	public static final String CMD_UPLOAD = "bds -upload";

	// Note:	It is important that 'options' node is type-checked before the others in order to
	//			add variables to the scope before statements uses them.
	//			So the field name should be alphabetically sorted before the other (that's why
	//			I call it 'options' and not 'taskOptions').
	//			Yes, it's a horrible hack.
	protected ExpressionTaskOptions options;
	protected Statement statement;
	protected boolean asmPushDeps;
	protected InterpolateVars preludeInterpolateVars;
	protected String preludeStr;

	public ExpressionTask(BdsNode parent, ParseTree tree) {
		super(parent, tree);
		asmPushDeps = true;
	}

	protected boolean hasPrelude() {
		return preludeInterpolateVars != null || (preludeStr != null && !preludeStr.isEmpty());
	}

	@Override
	public boolean isReturnTypesNotNull() {
		return true;
	}

	//	@Override
	//	public boolean isStopDebug() {
	//		return true;
	//	}

	@Override
	protected void parse(ParseTree tree) {
		int idx = 0;
		idx++; // 'task' keyword

		// Do we have any task options?
		if (tree.getChild(idx).getText().equals("(")) {
			int lastIdx = indexOf(tree, ")");

			options = new ExpressionTaskOptions(this, null);
			options.parse(tree, ++idx, lastIdx);
			idx = lastIdx + 1; // Skip last ')'
		}

		statement = (Statement) factory(tree, idx++); // Parse statement

		parsePrelude();
	}

	/**
	 * Parse prelude string from Config
	 */
	void parsePrelude() {
		String prelude = Config.get().getTaskPrelude();
		if (prelude == null || prelude.isEmpty()) {
			preludeStr = "";
			return;
		}

		preludeInterpolateVars = new InterpolateVars(this, null);
		if (!preludeInterpolateVars.parse(prelude)) {
			preludeInterpolateVars = null; // Nothing found? don't bother to keep the object
			preludeStr = GprString.unescapeDollar(prelude); // Just use literal, but un-escape dollar signs
		}
	}

	/**
	 * Task expression always returns the task id, which is a string
	 */
	@Override
	public Type returnType(SymbolTable symtab) {
		// Calculate options' return type
		if (options != null) options.returnType(symtab);
		if (statement != null) statement.returnType(symtab);

		// Task expressions return a task ID (a string)
		returnType = Types.STRING;
		return returnType;
	}

	@Override
	public void sanityCheck(CompilerMessages compilerMessages) {
		// Sanity check options
		if (options != null) options.sanityCheck(compilerMessages);

		// Sanity check statements
		if (statement == null) {
			compilerMessages.add(this, "Task has empty statement", MessageType.ERROR);
			return;
		}

		List<BdsNode> statements = BdsNodeWalker.findNodes(statement, null, true, false);

		// No child nodes? Add the only node we have
		if (statements.isEmpty()) statements.add(statement);

		for (BdsNode node : statements) {
			if (node instanceof Statement) {
				boolean ok = node instanceof ExpressionSys //
						|| node instanceof Block //
						|| node instanceof Literal //
						|| node instanceof InterpolateVars //
						|| node instanceof Reference //
						|| node instanceof StatementExpr //
				;

				if (!ok) compilerMessages.add(this, "Only sys statements are allowed in a task (line " + node.getLineNum() + ")", MessageType.ERROR);
			}
		}
	}

	@Override
	public String toAsm() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toAsmNode()); // Task will use the node to get parameters
		sb.append("scopepush\n");

		// Define labels
		String labelEnd = baseLabelName() + "end";
		String labelFalse = baseLabelName() + "false";

		// Options
		sb.append(toAsmOptions(labelFalse));

		// Command (e.g. task and statements)
		sb.append(toAsmCmd(labelEnd));

		// Task expression not evaluated because one or more bool expressions was false
		sb.append(labelFalse + ":\n");
		sb.append("pushs ''\n"); // Task not executed, push an empty task id

		// End of task expression
		sb.append(labelEnd + ":\n");
		sb.append("scopepop\n");
		return sb.toString();
	}

	/**
	 * Commands (i.e. task)
	 */
	protected String toAsmCmd(String labelEnd) {
		StringBuilder sb = new StringBuilder();

		if (hasPrelude()) sb.append(toAsmPrelude());
		sb.append(toAsmStatements()); // Statements (e.g.: sys commands)
		if (hasPrelude()) sb.append("adds\n");

		sb.append("task\n");
		sb.append("jmp " + labelEnd + "\n"); // Go to the end
		return sb.toString();
	}

	/**
	 * Options
	 */
	protected String toAsmOptions(String labelFalse) {
		StringBuilder sb = new StringBuilder();

		if (options != null) {
			sb.append(options.toAsm(labelFalse, asmPushDeps)); // Jump to 'labelFalse' if any of the bool expressions is false
		} else if (asmPushDeps) {
			// No options or dependencies.
			// Add empty list as dependency
			sb.append("new string[]\n");
			sb.append("new string[]\n");
		}
		return sb.toString();
	}

	protected String toAsmPrelude() {
		if (preludeInterpolateVars != null) return preludeInterpolateVars.toAsm();
		if (preludeStr != null && !preludeStr.isEmpty()) return "pushs " + preludeStr + "\n";
		return "";
	}

	/**
	 * Evaluate 'sys' statements used to create task
	 */
	protected String toAsmStatements() {
		// Only one 'sys' expression
		if (statement instanceof StatementExpr) {
			Expression exprSys = ((StatementExpr) statement).getExpression();
			ExpressionSys sys = (ExpressionSys) exprSys;
			return sys.toAsm(false);
		}

		// One 'sys' expression within a statement
		if (statement instanceof ExpressionSys) {
			ExpressionSys sys = (ExpressionSys) statement;
			return sys.toAsm(false);
		}

		// Multiple 'sys' expressions in a block
		if (statement instanceof Block) {
			// Create one sys statement for all sys statements in the block
			StringBuilder sb = new StringBuilder();

			Block block = (Block) statement;
			sb.append("new string\n");
			for (Statement st : block.getStatements()) {
				// Get 'sys' expression
				if (st instanceof StatementExpr) st = ((StatementExpr) st).getExpression();
				ExpressionSys sys = (ExpressionSys) st;
				sb.append(sys.toAsm(false));
				sb.append("adds\n");
			}

			return sb.toString();
		}

		throw new RuntimeException("Unimplemented for class '" + statement.getClass().getSimpleName() + "'");
	}

	@Override
	public String toString() {
		return "task" //
				+ (options != null ? options : "") //
				+ " " //
				+ toStringStatement() //
		;
	}

	/**
	 * Format statements
	 */
	protected String toStringStatement() {
		if (statement instanceof LiteralString) {
			// Compact single line
			return ((LiteralString) statement).getValue().asString().trim();
		}

		if (statement instanceof ExpressionSys || statement instanceof StatementExpr) {
			// Compact single line form
			return statement.toString();
		}

		// Multiline
		return "{\n" //
				+ Gpr.prependEachLine("\t", statement.toString()) //
				+ "}" //
		;
	}

	@Override
	public void typeCheck(SymbolTable symtab, CompilerMessages compilerMessages) {
		returnType(symtab);
		if (options != null) options.typeCheck(symtab, compilerMessages);
		if (statement != null) statement.typeCheck(symtab, compilerMessages);
	}

}
