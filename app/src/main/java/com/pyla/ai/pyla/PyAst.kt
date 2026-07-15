package com.pyla.ai.pyla

/** AST node definitions for the .pyla Python subset. */

sealed class Stmt { var line: Int = 0 }
sealed class Expr { var line: Int = 0 }

// ---- Statements ----

class AssignStmt(val targets: List<Expr>, val value: Expr) : Stmt()
class AugAssignStmt(val target: Expr, val op: String, val value: Expr) : Stmt()
class ExprStmt(val expr: Expr) : Stmt()
class IfStmt(val branches: List<Pair<Expr, List<Stmt>>>, val elseBody: List<Stmt>?) : Stmt()
class ForStmt(val target: Expr, val iter: Expr, val body: List<Stmt>, val elseBody: List<Stmt>?) : Stmt()
class WhileStmt(val cond: Expr, val body: List<Stmt>, val elseBody: List<Stmt>?) : Stmt()
class FuncDefStmt(val name: String, val params: List<Param>, val body: List<Stmt>) : Stmt()
class ReturnStmt(val value: Expr?) : Stmt()
class BreakStmt : Stmt()
class ContinueStmt : Stmt()
class PassStmt : Stmt()
class RaiseStmt(val exc: Expr?) : Stmt()
class GlobalStmt(val names: List<String>) : Stmt()
/** import / from-import are parsed but treated as no-ops (modules are pre-injected). */
class ImportStmt : Stmt()

class Param(val name: String, val default: Expr?)

// ---- Expressions ----

class NumLit(val value: Any) : Expr()          // Long or Double
class StrLit(val value: String) : Expr()
class BoolLit(val value: Boolean) : Expr()
class NoneLit : Expr()
class NameExpr(val id: String) : Expr()
class TupleExpr(val elts: List<Expr>) : Expr()
class ListExpr(val elts: List<Expr>) : Expr()
class DictExpr(val keys: List<Expr>, val values: List<Expr>) : Expr()
class BinOpExpr(val left: Expr, val op: String, val right: Expr) : Expr()
class BoolOpExpr(val op: String, val values: List<Expr>) : Expr()   // "and" / "or"
class UnaryOpExpr(val op: String, val operand: Expr) : Expr()       // "-", "+", "not", "~"
class CompareExpr(val left: Expr, val ops: List<String>, val comparators: List<Expr>) : Expr()
class CallExpr(val func: Expr, val args: List<Expr>, val kwargs: List<Pair<String, Expr>>) : Expr()
class AttributeExpr(val value: Expr, val attr: String) : Expr()
class SubscriptExpr(val value: Expr, val index: Expr) : Expr()
class SliceExpr(val lower: Expr?, val upper: Expr?, val step: Expr?) : Expr()
class IfExpr(val body: Expr, val test: Expr, val orElse: Expr) : Expr()
class JoinedStrExpr(val parts: List<Any>) : Expr()                  // String (literal) or Expr
class ListCompExpr(val elt: Expr, val target: Expr, val iter: Expr, val conditions: List<Expr>) : Expr()
