package com.pyla.ai.pyla

class PyParseException(message: String, val line: Int) : RuntimeException("Line $line: $message")

/**
 * Recursive-descent parser for the .pyla Python subset. Produces a list of statements.
 */
class PyParser(private val toks: List<Tok>) {

    private var i = 0

    companion object {
        private val AUG_OPS = setOf("+=", "-=", "*=", "/=", "//=", "%=", "**=")
        private val KEYWORDS = setOf(
            "if", "elif", "else", "for", "while", "in", "and", "or", "not", "is",
            "def", "return", "break", "continue", "pass", "raise", "import", "from",
            "as", "None", "True", "False", "global", "nonlocal", "del", "lambda",
        )
    }

    private fun peek(): Tok = toks[i]
    private fun peek2(): Tok = if (i + 1 < toks.size) toks[i + 1] else toks[toks.size - 1]
    private fun next(): Tok = toks[i++]
    private fun atEnd(): Boolean = peek().type == TokType.EOF

    private fun isOp(v: String): Boolean = peek().type == TokType.OP && peek().value == v
    private fun isName(v: String): Boolean = peek().type == TokType.NAME && peek().value == v

    private fun expectOp(v: String) {
        if (!isOp(v)) throw PyParseException("expected '$v' but got '${peek().value}'", peek().line)
        next()
    }

    private fun expectName(v: String) {
        if (!isName(v)) throw PyParseException("expected '$v'", peek().line)
        next()
    }

    private fun skipNewlines() {
        while (peek().type == TokType.NEWLINE) next()
    }

    fun parseModule(): List<Stmt> {
        val stmts = ArrayList<Stmt>()
        skipNewlines()
        while (!atEnd()) {
            stmts.addAll(parseStatement())
            skipNewlines()
        }
        return stmts
    }

    // Returns a list because a simple-statement line can hold several separated by ';'
    private fun parseStatement(): List<Stmt> {
        val t = peek()
        if (t.type == TokType.NAME && t.value in setOf("if", "for", "while", "def")) {
            return listOf(parseCompound())
        }
        return parseSimpleLine()
    }

    private fun parseCompound(): Stmt {
        return when (peek().value) {
            "if" -> parseIf()
            "for" -> parseFor()
            "while" -> parseWhile()
            "def" -> parseDef()
            else -> throw PyParseException("unknown compound '${peek().value}'", peek().line)
        }
    }

    private fun parseSuite(): List<Stmt> {
        // Either ": simple ; simple NEWLINE"  or  ": NEWLINE INDENT stmts DEDENT"
        expectOp(":")
        if (peek().type == TokType.NEWLINE) {
            next()
            skipNewlines()
            if (peek().type != TokType.INDENT) {
                throw PyParseException("expected an indented block", peek().line)
            }
            next() // INDENT
            val stmts = ArrayList<Stmt>()
            skipNewlines()
            while (peek().type != TokType.DEDENT && !atEnd()) {
                stmts.addAll(parseStatement())
                skipNewlines()
            }
            if (peek().type == TokType.DEDENT) next()
            return stmts
        } else {
            // inline suite on same line
            return parseSimpleLine()
        }
    }

    private fun parseIf(): Stmt {
        val branches = ArrayList<Pair<Expr, List<Stmt>>>()
        val line = peek().line
        expectName("if")
        val cond = parseExpr()
        val body = parseSuite()
        branches.add(cond to body)
        var elseBody: List<Stmt>? = null
        while (isName("elif")) {
            next()
            val c = parseExpr()
            val b = parseSuite()
            branches.add(c to b)
        }
        if (isName("else")) {
            next()
            elseBody = parseSuite()
        }
        return IfStmt(branches, elseBody).also { it.line = line }
    }

    private fun parseFor(): Stmt {
        val line = peek().line
        expectName("for")
        val target = parseTargetList()
        expectName("in")
        val iter = parseExprList()
        val body = parseSuite()
        var elseBody: List<Stmt>? = null
        if (isName("else")) { next(); elseBody = parseSuite() }
        return ForStmt(target, iter, body, elseBody).also { it.line = line }
    }

    private fun parseWhile(): Stmt {
        val line = peek().line
        expectName("while")
        val cond = parseExpr()
        val body = parseSuite()
        var elseBody: List<Stmt>? = null
        if (isName("else")) { next(); elseBody = parseSuite() }
        return WhileStmt(cond, body, elseBody).also { it.line = line }
    }

    private fun parseDef(): Stmt {
        val line = peek().line
        expectName("def")
        if (peek().type != TokType.NAME) throw PyParseException("expected function name", peek().line)
        val name = next().value
        expectOp("(")
        val params = ArrayList<Param>()
        while (!isOp(")")) {
            if (isOp("*") || isOp("**")) {
                // *args / **kwargs: consume name, ignore (best effort)
                next()
                if (peek().type == TokType.NAME) next()
            } else {
                if (peek().type != TokType.NAME) throw PyParseException("expected parameter name", peek().line)
                val pname = next().value
                var default: Expr? = null
                if (isOp("=")) { next(); default = parseExpr() }
                params.add(Param(pname, default))
            }
            if (isOp(",")) next() else break
        }
        expectOp(")")
        val body = parseSuite()
        return FuncDefStmt(name, params, body).also { it.line = line }
    }

    private fun parseSimpleLine(): List<Stmt> {
        val stmts = ArrayList<Stmt>()
        stmts.add(parseSmallStmt())
        while (isOp(";")) {
            next()
            if (peek().type == TokType.NEWLINE || atEnd()) break
            stmts.add(parseSmallStmt())
        }
        if (peek().type == TokType.NEWLINE) next()
        return stmts
    }

    private fun parseSmallStmt(): Stmt {
        val t = peek()
        if (t.type == TokType.NAME) {
            when (t.value) {
                "return" -> {
                    val line = next().line
                    val value = if (peek().type == TokType.NEWLINE || isOp(";") || atEnd()) null else parseExprList()
                    return ReturnStmt(value).also { it.line = line }
                }
                "pass" -> { val l = next().line; return PassStmt().also { it.line = l } }
                "break" -> { val l = next().line; return BreakStmt().also { it.line = l } }
                "continue" -> { val l = next().line; return ContinueStmt().also { it.line = l } }
                "raise" -> {
                    val line = next().line
                    val exc = if (peek().type == TokType.NEWLINE || isOp(";") || atEnd()) null else parseExpr()
                    return RaiseStmt(exc).also { it.line = line }
                }
                "import" -> { val l = next().line; skipToLineEnd(); return ImportStmt().also { it.line = l } }
                "from" -> { val l = next().line; skipToLineEnd(); return ImportStmt().also { it.line = l } }
                "global", "nonlocal" -> {
                    val l = next().line
                    val names = ArrayList<String>()
                    while (peek().type == TokType.NAME) {
                        names.add(next().value)
                        if (isOp(",")) next() else break
                    }
                    return GlobalStmt(names).also { it.line = l }
                }
                "del" -> { val l = next().line; skipToLineEnd(); return PassStmt().also { it.line = l } }
            }
        }
        // expression / assignment
        val line = peek().line
        val first = parseExprList()
        if (peek().type == TokType.OP && peek().value in AUG_OPS) {
            val op = next().value
            val value = parseExprList()
            return AugAssignStmt(first, op, value).also { it.line = line }
        }
        if (isOp("=")) {
            val targets = ArrayList<Expr>()
            targets.add(first)
            var value: Expr = first
            while (isOp("=")) {
                next()
                value = parseExprList()
                targets.add(value)
            }
            // last parsed is the value, the rest are targets
            val realValue = targets.removeAt(targets.size - 1)
            return AssignStmt(targets, realValue).also { it.line = line }
        }
        return ExprStmt(first).also { it.line = line }
    }

    private fun skipToLineEnd() {
        while (peek().type != TokType.NEWLINE && !atEnd()) next()
    }

    // ---- target lists (for-loop / assignment LHS) ----

    private fun parseTargetList(): Expr {
        val first = parseAtomTrailer()
        if (!isOp(",")) return first
        val elts = ArrayList<Expr>()
        elts.add(first)
        while (isOp(",")) {
            next()
            if (isName("in") || peek().type == TokType.OP && peek().value == "=") break
            elts.add(parseAtomTrailer())
        }
        return TupleExpr(elts)
    }

    // ---- expression lists (may build a tuple) ----

    private fun parseExprList(): Expr {
        val first = parseExpr()
        if (!isOp(",")) return first
        val elts = ArrayList<Expr>()
        elts.add(first)
        while (isOp(",")) {
            next()
            if (peek().type == TokType.NEWLINE || isOp("=") || isOp(")") || isOp("]") || isOp("}") ||
                isOp(":") || isOp(";") || atEnd()) break
            elts.add(parseExpr())
        }
        return TupleExpr(elts)
    }

    // ---- expression precedence ----

    private fun parseExpr(): Expr = parseTernary()

    private fun parseTernary(): Expr {
        val body = parseOr()
        if (isName("if")) {
            next()
            val test = parseOr()
            expectName("else")
            val orElse = parseTernary()
            return IfExpr(body, test, orElse).also { it.line = body.line }
        }
        return body
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        if (isName("or")) {
            val values = ArrayList<Expr>()
            values.add(left)
            while (isName("or")) { next(); values.add(parseAnd()) }
            left = BoolOpExpr("or", values).also { it.line = values.first().line }
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseNot()
        if (isName("and")) {
            val values = ArrayList<Expr>()
            values.add(left)
            while (isName("and")) { next(); values.add(parseNot()) }
            left = BoolOpExpr("and", values).also { it.line = values.first().line }
        }
        return left
    }

    private fun parseNot(): Expr {
        if (isName("not")) {
            val line = next().line
            val operand = parseNot()
            return UnaryOpExpr("not", operand).also { it.line = line }
        }
        return parseComparison()
    }

    private fun parseComparison(): Expr {
        val left = parseArith()
        val ops = ArrayList<String>()
        val comps = ArrayList<Expr>()
        while (true) {
            val op = matchComparisonOp() ?: break
            ops.add(op)
            comps.add(parseArith())
        }
        if (ops.isEmpty()) return left
        return CompareExpr(left, ops, comps).also { it.line = left.line }
    }

    private fun matchComparisonOp(): String? {
        val t = peek()
        if (t.type == TokType.OP && t.value in setOf("<", ">", "<=", ">=", "==", "!=")) {
            next(); return t.value
        }
        if (t.type == TokType.NAME) {
            when (t.value) {
                "in" -> { next(); return "in" }
                "not" -> {
                    if (peek2().type == TokType.NAME && peek2().value == "in") { next(); next(); return "not in" }
                    return null
                }
                "is" -> {
                    next()
                    if (isName("not")) { next(); return "is not" }
                    return "is"
                }
            }
        }
        return null
    }

    private fun parseArith(): Expr {
        var left = parseTerm()
        while (isOp("+") || isOp("-")) {
            val op = next().value
            val right = parseTerm()
            left = BinOpExpr(left, op, right).also { it.line = left.line }
        }
        return left
    }

    private fun parseTerm(): Expr {
        var left = parseFactor()
        while (isOp("*") || isOp("/") || isOp("//") || isOp("%")) {
            val op = next().value
            val right = parseFactor()
            left = BinOpExpr(left, op, right).also { it.line = left.line }
        }
        return left
    }

    private fun parseFactor(): Expr {
        if (isOp("-") || isOp("+") || isOp("~")) {
            val line = peek().line
            val op = next().value
            val operand = parseFactor()
            return UnaryOpExpr(op, operand).also { it.line = line }
        }
        return parsePower()
    }

    private fun parsePower(): Expr {
        val base = parseAtomTrailer()
        if (isOp("**")) {
            next()
            val exp = parseFactor() // right-associative, allows unary on exponent
            return BinOpExpr(base, "**", exp).also { it.line = base.line }
        }
        return base
    }

    private fun parseAtomTrailer(): Expr {
        var e = parseAtom()
        while (true) {
            when {
                isOp(".") -> {
                    next()
                    if (peek().type != TokType.NAME) throw PyParseException("expected attribute name", peek().line)
                    val attr = next().value
                    e = AttributeExpr(e, attr).also { it.line = e.line }
                }
                isOp("(") -> {
                    e = parseCall(e)
                }
                isOp("[") -> {
                    next()
                    val idx = parseSubscript()
                    expectOp("]")
                    e = SubscriptExpr(e, idx).also { it.line = e.line }
                }
                else -> break
            }
        }
        return e
    }

    private fun parseSubscript(): Expr {
        // supports slices a:b:c
        var lower: Expr? = null
        var upper: Expr? = null
        var step: Expr? = null
        var isSlice = false
        if (!isOp(":")) lower = parseExpr()
        if (isOp(":")) {
            isSlice = true
            next()
            if (!isOp("]") && !isOp(":")) upper = parseExpr()
            if (isOp(":")) {
                next()
                if (!isOp("]")) step = parseExpr()
            }
        }
        return if (isSlice) SliceExpr(lower, upper, step) else lower!!
    }

    private fun parseCall(func: Expr): Expr {
        expectOp("(")
        val args = ArrayList<Expr>()
        val kwargs = ArrayList<Pair<String, Expr>>()
        while (!isOp(")")) {
            if (isOp("*") || isOp("**")) { next() /* unpack: best effort */ }
            if (peek().type == TokType.NAME && peek2().type == TokType.OP && peek2().value == "=") {
                val name = next().value
                next() // =
                val v = parseExpr()
                kwargs.add(name to v)
            } else {
                args.add(parseExpr())
            }
            if (isOp(",")) next() else break
        }
        expectOp(")")
        return CallExpr(func, args, kwargs).also { it.line = func.line }
    }

    private fun parseAtom(): Expr {
        val t = peek()
        when (t.type) {
            TokType.NUMBER -> { next(); return NumLit(t.payload!!).also { it.line = t.line } }
            TokType.STRING -> { next(); return StrLit(t.payload as String).also { it.line = t.line } }
            TokType.FSTRING -> { next(); return buildJoinedStr(t) }
            TokType.NAME -> {
                when (t.value) {
                    "None" -> { next(); return NoneLit().also { it.line = t.line } }
                    "True" -> { next(); return BoolLit(true).also { it.line = t.line } }
                    "False" -> { next(); return BoolLit(false).also { it.line = t.line } }
                    "not", "lambda" -> throw PyParseException("unexpected '${t.value}'", t.line)
                }
                if (t.value in KEYWORDS) throw PyParseException("unexpected keyword '${t.value}'", t.line)
                next()
                return NameExpr(t.value).also { it.line = t.line }
            }
            TokType.OP -> {
                when (t.value) {
                    "(" -> return parseParenOrTuple()
                    "[" -> return parseListOrComp()
                    "{" -> return parseDict()
                }
            }
            else -> {}
        }
        throw PyParseException("unexpected token '${t.value}'", t.line)
    }

    private fun buildJoinedStr(t: Tok): Expr {
        @Suppress("UNCHECKED_CAST")
        val parts = t.payload as List<FPart>
        val out = ArrayList<Any>()
        for (p in parts) {
            when (p) {
                is FPart.Lit -> out.add(p.text)
                is FPart.Expr -> {
                    val subToks = PyLexer(p.source).tokenize()
                    val subParser = PyParser(subToks)
                    val e = subParser.parseSingleExpr()
                    out.add(e)
                }
            }
        }
        return JoinedStrExpr(out).also { it.line = t.line }
    }

    fun parseSingleExpr(): Expr {
        skipNewlines()
        val e = parseExpr()
        return e
    }

    private fun parseParenOrTuple(): Expr {
        val line = peek().line
        expectOp("(")
        if (isOp(")")) { next(); return TupleExpr(emptyList()).also { it.line = line } }
        val first = parseExpr()
        if (isOp(",")) {
            val elts = ArrayList<Expr>()
            elts.add(first)
            while (isOp(",")) {
                next()
                if (isOp(")")) break
                elts.add(parseExpr())
            }
            expectOp(")")
            return TupleExpr(elts).also { it.line = line }
        }
        expectOp(")")
        return first
    }

    private fun parseListOrComp(): Expr {
        val line = peek().line
        expectOp("[")
        if (isOp("]")) { next(); return ListExpr(emptyList()).also { it.line = line } }
        val first = parseExpr()
        if (isName("for")) {
            // list comprehension
            next()
            val target = parseTargetList()
            expectName("in")
            val iter = parseOr()
            val conds = ArrayList<Expr>()
            while (isName("if")) { next(); conds.add(parseOr()) }
            expectOp("]")
            return ListCompExpr(first, target, iter, conds).also { it.line = line }
        }
        val elts = ArrayList<Expr>()
        elts.add(first)
        while (isOp(",")) {
            next()
            if (isOp("]")) break
            elts.add(parseExpr())
        }
        expectOp("]")
        return ListExpr(elts).also { it.line = line }
    }

    private fun parseDict(): Expr {
        val line = peek().line
        expectOp("{")
        val keys = ArrayList<Expr>()
        val values = ArrayList<Expr>()
        while (!isOp("}")) {
            val k = parseExpr()
            expectOp(":")
            val v = parseExpr()
            keys.add(k); values.add(v)
            if (isOp(",")) next() else break
        }
        expectOp("}")
        return DictExpr(keys, values).also { it.line = line }
    }
}
