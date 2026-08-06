package com.pyla.ai.pyla

import kotlin.math.abs
import kotlin.random.Random

private class BreakSignal : RuntimeException()
private class ContinueSignal : RuntimeException()
private class ReturnSignal(val value: Any) : RuntimeException()

class Scope(val vars: MutableMap<String, Any> = HashMap(), val parent: Scope? = null) {
    val globalNames = HashSet<String>()
    fun lookup(name: String): Any? {
        var s: Scope? = this
        while (s != null) {
            val v = s.vars[name]
            if (v != null || s.vars.containsKey(name)) return v
            s = s.parent
        }
        return null
    }
    fun has(name: String): Boolean {
        var s: Scope? = this
        while (s != null) { if (s.vars.containsKey(name)) return true; s = s.parent }
        return false
    }
}

class PyFunction(
    val name: String,
    val params: List<Param>,
    val body: List<Stmt>,
    val closure: Scope,
    val interp: PyInterpreter,
) : PyCallable {
    override fun call(args: List<Any>, kwargs: Map<String, Any>): Any {
        val local = Scope(HashMap(), closure)
        for ((idx, p) in params.withIndex()) {
            val v: Any = when {
                idx < args.size -> args[idx]
                kwargs.containsKey(p.name) -> kwargs.getValue(p.name)
                p.default != null -> interp.eval(p.default, closure)
                else -> throw PyRuntimeError("$name() missing required argument '${p.name}'")
            }
            local.vars[p.name] = v
        }
        return try {
            interp.execBlock(body, local)
            PyNone
        } catch (r: ReturnSignal) {
            r.value
        }
    }
    override fun toString(): String = "<function $name>"
}

class PyInterpreter(val globals: Scope) {

    private val rng = Random(System.nanoTime())

    fun execBlock(stmts: List<Stmt>, scope: Scope) {
        for (s in stmts) exec(s, scope)
    }

    fun exec(stmt: Stmt, scope: Scope) {
        when (stmt) {
            is ExprStmt -> eval(stmt.expr, scope)
            is AssignStmt -> {
                val value = eval(stmt.value, scope)
                for (target in stmt.targets) assign(target, value, scope)
            }
            is AugAssignStmt -> {
                val current = eval(stmt.target, scope)
                val rhs = eval(stmt.value, scope)
                val op = stmt.op.dropLast(1) // "+=" -> "+"
                assign(stmt.target, Py.binaryOp(op, current, rhs), scope)
            }
            is IfStmt -> {
                for ((cond, body) in stmt.branches) {
                    if (Py.truthy(eval(cond, scope))) { execBlock(body, scope); return }
                }
                stmt.elseBody?.let { execBlock(it, scope) }
            }
            is ForStmt -> execFor(stmt, scope)
            is WhileStmt -> execWhile(stmt, scope)
            is FuncDefStmt -> {
                scope.vars[stmt.name] = PyFunction(stmt.name, stmt.params, stmt.body, scope, this)
            }
            is ReturnStmt -> throw ReturnSignal(stmt.value?.let { eval(it, scope) } ?: PyNone)
            is BreakStmt -> throw BreakSignal()
            is ContinueStmt -> throw ContinueSignal()
            is PassStmt -> {}
            is RaiseStmt -> {
                val v = stmt.exc?.let { eval(it, scope) } ?: PyException("Exception", "")
                throw PyRaiseException(v)
            }
            is GlobalStmt -> scope.globalNames.addAll(stmt.names)
            is ImportStmt -> {}
        }
    }

    private fun execFor(stmt: ForStmt, scope: Scope) {
        val iterable = Py.iterate(eval(stmt.iter, scope))
        var broke = false
        loop@ for (item in iterable) {
            assign(stmt.target, item, scope)
            try {
                execBlock(stmt.body, scope)
            } catch (b: BreakSignal) { broke = true; break@loop }
            catch (c: ContinueSignal) { continue@loop }
        }
        if (!broke) stmt.elseBody?.let { execBlock(it, scope) }
    }

    private fun execWhile(stmt: WhileStmt, scope: Scope) {
        var broke = false
        var guard = 0
        while (Py.truthy(eval(stmt.cond, scope))) {
            if (++guard > 5_000_000) throw PyRuntimeError("while loop exceeded iteration limit")
            try {
                execBlock(stmt.body, scope)
            } catch (b: BreakSignal) { broke = true; break }
            catch (c: ContinueSignal) { continue }
        }
        if (!broke) stmt.elseBody?.let { execBlock(it, scope) }
    }

    // ---- assignment ----

    private fun assign(target: Expr, value: Any, scope: Scope) {
        when (target) {
            is NameExpr -> {
                if (target.id in scope.globalNames) globals.vars[target.id] = value
                else scope.vars[target.id] = value
            }
            is TupleExpr -> unpackAssign(target.elts, value, scope)
            is ListExpr -> unpackAssign(target.elts, value, scope)
            is SubscriptExpr -> {
                val container = eval(target.value, scope)
                val index = eval(target.index, scope)
                Py.setIndex(container, index, value)
            }
            else -> throw PyRuntimeError("cannot assign to this expression")
        }
    }

    private fun unpackAssign(targets: List<Expr>, value: Any, scope: Scope) {
        val items = when (value) {
            is PyList -> value.items
            is PyTuple -> value.items
            is String -> value.map { it.toString() as Any }
            else -> throw PyRuntimeError("cannot unpack non-sequence ${Py.typeName(value)}")
        }
        if (items.size != targets.size)
            throw PyRuntimeError("unpack mismatch: expected ${targets.size}, got ${items.size}")
        for (idx in targets.indices) assign(targets[idx], items[idx], scope)
    }

    // ---- evaluation ----

    fun eval(expr: Expr, scope: Scope): Any {
        return when (expr) {
            is NumLit -> expr.value
            is StrLit -> expr.value
            is BoolLit -> expr.value
            is NoneLit -> PyNone
            is NameExpr -> {
                if (!scope.has(expr.id)) throw PyRuntimeError("name '${expr.id}' is not defined")
                scope.lookup(expr.id) ?: PyNone
            }
            is TupleExpr -> PyTuple(expr.elts.map { eval(it, scope) })
            is ListExpr -> PyList(expr.elts.map { eval(it, scope) }.toMutableList())
            is DictExpr -> {
                val m = LinkedHashMap<Any, Any>()
                for (idx in expr.keys.indices) m[eval(expr.keys[idx], scope)] = eval(expr.values[idx], scope)
                PyDict(m)
            }
            is BinOpExpr -> Py.binaryOp(expr.op, eval(expr.left, scope), eval(expr.right, scope))
            is BoolOpExpr -> evalBoolOp(expr, scope)
            is UnaryOpExpr -> evalUnary(expr, scope)
            is CompareExpr -> evalCompare(expr, scope)
            is CallExpr -> evalCall(expr, scope)
            is AttributeExpr -> getAttribute(eval(expr.value, scope), expr.attr)
            is SubscriptExpr -> {
                val container = eval(expr.value, scope)
                if (expr.index is SliceExpr) {
                    val s = expr.index
                    Py.getSlice(container,
                        s.lower?.let { eval(it, scope) },
                        s.upper?.let { eval(it, scope) },
                        s.step?.let { eval(it, scope) })
                } else {
                    Py.getIndex(container, eval(expr.index, scope))
                }
            }
            is IfExpr -> if (Py.truthy(eval(expr.test, scope))) eval(expr.body, scope) else eval(expr.orElse, scope)
            is JoinedStrExpr -> {
                val sb = StringBuilder()
                for (p in expr.parts) {
                    if (p is String) sb.append(p)
                    else sb.append(Py.str(eval(p as Expr, scope)))
                }
                sb.toString()
            }
            is ListCompExpr -> evalListComp(expr, scope)
            is SliceExpr -> throw PyRuntimeError("slice used outside subscript")
        }
    }

    private fun evalBoolOp(expr: BoolOpExpr, scope: Scope): Any {
        if (expr.op == "and") {
            var last: Any = true
            for (v in expr.values) { last = eval(v, scope); if (!Py.truthy(last)) return last }
            return last
        } else {
            var last: Any = false
            for (v in expr.values) { last = eval(v, scope); if (Py.truthy(last)) return last }
            return last
        }
    }

    private fun evalUnary(expr: UnaryOpExpr, scope: Scope): Any {
        val v = eval(expr.operand, scope)
        return when (expr.op) {
            "not" -> !Py.truthy(v)
            "-" -> if (v is Long) -v else -Py.toDouble(v)
            "+" -> if (Py.isNumber(v)) v else throw PyRuntimeError("bad operand for unary +")
            "~" -> Py.toLong(v).inv()
            else -> throw PyRuntimeError("unknown unary ${expr.op}")
        }
    }

    private fun evalCompare(expr: CompareExpr, scope: Scope): Any {
        var left = eval(expr.left, scope)
        for (idx in expr.ops.indices) {
            val right = eval(expr.comparators[idx], scope)
            val ok = when (expr.ops[idx]) {
                "<" -> Py.compare(left, right) < 0
                ">" -> Py.compare(left, right) > 0
                "<=" -> Py.compare(left, right) <= 0
                ">=" -> Py.compare(left, right) >= 0
                "==" -> Py.eq(left, right)
                "!=" -> !Py.eq(left, right)
                "in" -> Py.contains(right, left)
                "not in" -> !Py.contains(right, left)
                "is" -> identity(left, right)
                "is not" -> !identity(left, right)
                else -> throw PyRuntimeError("bad comparison ${expr.ops[idx]}")
            }
            if (!ok) return false
            left = right
        }
        return true
    }

    private fun identity(a: Any, b: Any): Boolean {
        if (a is PyNone || b is PyNone) return a is PyNone && b is PyNone
        if (a is Boolean && b is Boolean) return a == b
        return a === b
    }

    private fun evalListComp(expr: ListCompExpr, scope: Scope): Any {
        val out = ArrayList<Any>()
        val iterable = Py.iterate(eval(expr.iter, scope))
        val local = Scope(HashMap(), scope)
        for (item in iterable) {
            assign(expr.target, item, local)
            var keep = true
            for (c in expr.conditions) if (!Py.truthy(eval(c, local))) { keep = false; break }
            if (keep) out.add(eval(expr.elt, local))
        }
        return PyList(out)
    }

    private fun evalCall(expr: CallExpr, scope: Scope): Any {
        val fn = eval(expr.func, scope)
        val args = expr.args.map { eval(it, scope) }
        val kwargs = if (expr.kwargs.isEmpty()) emptyMap() else LinkedHashMap<String, Any>().apply {
            for ((k, v) in expr.kwargs) put(k, eval(v, scope))
        }
        if (fn !is PyCallable) throw PyRuntimeError("'${Py.typeName(fn)}' object is not callable")
        return fn.call(args, kwargs)
    }

    // ---- attribute / method access ----

    fun getAttribute(obj: Any, name: String): Any {
        return when (obj) {
            is PyModule -> obj.members[name]
                ?: throw PyRuntimeError("module '${obj.name}' has no attribute '$name'")
            is PyDict -> dictMethod(obj, name)
            is PyList -> listMethod(obj, name)
            is String -> strMethod(obj, name)
            is PyTuple -> tupleMethod(obj, name)
            else -> throw PyRuntimeError("'${Py.typeName(obj)}' object has no attribute '$name'")
        }
    }

    private fun arg(args: List<Any>, i: Int, default: Any): Any = if (i < args.size) args[i] else default

    private fun dictMethod(d: PyDict, name: String): Any = when (name) {
        "get" -> NativeFn("dict.get") { a, _ ->
            val key = a[0]
            d.map.entries.firstOrNull { Py.eq(it.key, key) }?.value ?: arg(a, 1, PyNone)
        }
        "setdefault" -> NativeFn("dict.setdefault") { a, _ ->
            val key = a[0]
            val existing = d.map.entries.firstOrNull { Py.eq(it.key, key) }
            if (existing != null) existing.value
            else { val def = arg(a, 1, PyNone); d.map[key] = def; def }
        }
        "keys" -> NativeFn("dict.keys") { _, _ -> PyList(ArrayList(d.map.keys)) }
        "values" -> NativeFn("dict.values") { _, _ -> PyList(ArrayList(d.map.values)) }
        "items" -> NativeFn("dict.items") { _, _ ->
            PyList(d.map.entries.map { PyTuple(listOf(it.key, it.value)) as Any }.toMutableList())
        }
        "pop" -> NativeFn("dict.pop") { a, _ ->
            val key = a[0]
            val e = d.map.entries.firstOrNull { Py.eq(it.key, key) }
            if (e != null) { d.map.remove(e.key); e.value }
            else if (a.size > 1) a[1]
            else throw PyRaiseException(PyException("KeyError", Py.repr(key)))
        }
        "update" -> NativeFn("dict.update") { a, _ ->
            val other = a[0]
            if (other is PyDict) for ((k, v) in other.map) Py.setIndex(d, k, v)
            PyNone
        }
        "copy" -> NativeFn("dict.copy") { _, _ -> PyDict(LinkedHashMap(d.map)) }
        "clear" -> NativeFn("dict.clear") { _, _ -> d.map.clear(); PyNone }
        "__contains__" -> NativeFn("dict.__contains__") { a, _ -> Py.contains(d, a[0]) }
        else -> throw PyRuntimeError("'dict' object has no attribute '$name'")
    }

    private fun listMethod(l: PyList, name: String): Any = when (name) {
        "append" -> NativeFn("list.append") { a, _ -> l.items.add(a[0]); PyNone }
        "extend" -> NativeFn("list.extend") { a, _ -> l.items.addAll(Py.iterate(a[0])); PyNone }
        "remove" -> NativeFn("list.remove") { a, _ ->
            val idx = l.items.indexOfFirst { Py.eq(it, a[0]) }
            if (idx < 0) throw PyRaiseException(PyException("ValueError", "list.remove(x): x not in list"))
            l.items.removeAt(idx); PyNone
        }
        "pop" -> NativeFn("list.pop") { a, _ ->
            if (l.items.isEmpty()) throw PyRaiseException(PyException("IndexError", "pop from empty list"))
            val i = if (a.isEmpty()) l.items.size - 1 else Py.toLong(a[0]).toInt().let { if (it < 0) it + l.items.size else it }
            l.items.removeAt(i)
        }
        "insert" -> NativeFn("list.insert") { a, _ ->
            var i = Py.toLong(a[0]).toInt()
            if (i < 0) i += l.items.size
            if (i < 0) i = 0
            if (i > l.items.size) i = l.items.size
            l.items.add(i, a[1]); PyNone
        }
        "index" -> NativeFn("list.index") { a, _ ->
            val idx = l.items.indexOfFirst { Py.eq(it, a[0]) }
            if (idx < 0) throw PyRaiseException(PyException("ValueError", "${Py.repr(a[0])} is not in list"))
            idx.toLong()
        }
        "count" -> NativeFn("list.count") { a, _ -> l.items.count { Py.eq(it, a[0]) }.toLong() }
        "sort" -> NativeFn("list.sort") { _, kw -> sortInPlace(l.items, kw); PyNone }
        "reverse" -> NativeFn("list.reverse") { _, _ -> l.items.reverse(); PyNone }
        "copy" -> NativeFn("list.copy") { _, _ -> PyList(ArrayList(l.items)) }
        "clear" -> NativeFn("list.clear") { _, _ -> l.items.clear(); PyNone }
        else -> throw PyRuntimeError("'list' object has no attribute '$name'")
    }

    private fun tupleMethod(t: PyTuple, name: String): Any = when (name) {
        "index" -> NativeFn("tuple.index") { a, _ ->
            val idx = t.items.indexOfFirst { Py.eq(it, a[0]) }
            if (idx < 0) throw PyRaiseException(PyException("ValueError", "not in tuple"))
            idx.toLong()
        }
        "count" -> NativeFn("tuple.count") { a, _ -> t.items.count { Py.eq(it, a[0]) }.toLong() }
        else -> throw PyRuntimeError("'tuple' object has no attribute '$name'")
    }

    private fun sortInPlace(items: MutableList<Any>, kw: Map<String, Any>) {
        val key = kw["key"]
        val reverse = kw["reverse"]?.let { Py.truthy(it) } ?: false
        val cmp = Comparator<Any> { a, b ->
            val ka = if (key is PyCallable) key.call(listOf(a), emptyMap()) else a
            val kb = if (key is PyCallable) key.call(listOf(b), emptyMap()) else b
            Py.compare(ka, kb)
        }
        items.sortWith(if (reverse) cmp.reversed() else cmp)
    }

    private fun strMethod(s: String, name: String): Any = when (name) {
        "lower" -> NativeFn("str.lower") { _, _ -> s.lowercase() }
        "upper" -> NativeFn("str.upper") { _, _ -> s.uppercase() }
        "strip" -> NativeFn("str.strip") { a, _ -> if (a.isEmpty()) s.trim() else s.trim { it in (a[0] as String) } }
        "lstrip" -> NativeFn("str.lstrip") { a, _ -> if (a.isEmpty()) s.trimStart() else s.trimStart { it in (a[0] as String) } }
        "rstrip" -> NativeFn("str.rstrip") { a, _ -> if (a.isEmpty()) s.trimEnd() else s.trimEnd { it in (a[0] as String) } }
        "split" -> NativeFn("str.split") { a, _ ->
            val parts = if (a.isEmpty() || a[0] is PyNone) s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            else s.split(a[0] as String)
            PyList(parts.map { it as Any }.toMutableList())
        }
        "replace" -> NativeFn("str.replace") { a, _ -> s.replace(a[0] as String, a[1] as String) }
        "startswith" -> NativeFn("str.startswith") { a, _ -> s.startsWith(a[0] as String) }
        "endswith" -> NativeFn("str.endswith") { a, _ -> s.endsWith(a[0] as String) }
        "find" -> NativeFn("str.find") { a, _ -> s.indexOf(a[0] as String).toLong() }
        "count" -> NativeFn("str.count") { a, _ ->
            val sub = a[0] as String
            if (sub.isEmpty()) (s.length + 1).toLong()
            else { var c = 0; var idx = s.indexOf(sub); while (idx >= 0) { c++; idx = s.indexOf(sub, idx + sub.length) }; c.toLong() }
        }
        "capitalize" -> NativeFn("str.capitalize") { _, _ -> s.replaceFirstChar { it.uppercase() } }
        "join" -> NativeFn("str.join") { a, _ ->
            Py.iterate(a[0]).joinToString(s) { Py.str(it) }
        }
        "format" -> NativeFn("str.format") { a, _ -> pyFormat(s, a) }
        else -> throw PyRuntimeError("'str' object has no attribute '$name'")
    }

    private fun pyFormat(fmt: String, args: List<Any>): String {
        var idx = 0
        val sb = StringBuilder()
        var i = 0
        while (i < fmt.length) {
            val c = fmt[i]
            if (c == '{') {
                if (i + 1 < fmt.length && fmt[i + 1] == '{') { sb.append('{'); i += 2; continue }
                val end = fmt.indexOf('}', i)
                if (end < 0) { sb.append(c); i++; continue }
                val spec = fmt.substring(i + 1, end).substringBefore(':').substringBefore('!')
                val v = if (spec.isEmpty()) args.getOrElse(idx++) { "" } else args.getOrElse(spec.toIntOrNull() ?: 0) { "" }
                sb.append(Py.str(v))
                i = end + 1
            } else if (c == '}') {
                if (i + 1 < fmt.length && fmt[i + 1] == '}') { sb.append('}'); i += 2; continue }
                i++
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    // ---- builtins / module registration ----

    companion object {

        fun buildGlobals(context: Map<String, Any>): Scope {
            val scope = Scope()
            installBuiltins(scope.vars)
            for ((k, v) in context) scope.vars[k] = v
            return scope
        }

        private fun installBuiltins(g: MutableMap<String, Any>) {
            val rng = Random(System.nanoTime())

            g["abs"] = NativeFn("abs") { a, _ ->
                val v = a[0]; if (v is Long) abs(v) else abs(Py.toDouble(v))
            }
            g["min"] = NativeFn("min") { a, kw -> minMax(a, kw, true) }
            g["max"] = NativeFn("max") { a, kw -> minMax(a, kw, false) }
            g["sum"] = NativeFn("sum") { a, _ ->
                val items = Py.iterate(a[0])
                var acc: Any = if (a.size > 1) a[1] else 0L
                for (it in items) acc = Py.binaryOp("+", acc, it)
                acc
            }
            g["round"] = NativeFn("round") { a, _ ->
                val x = Py.toDouble(a[0])
                if (a.size > 1) {
                    val nd = Py.toLong(a[1]).toInt()
                    val factor = Math.pow(10.0, nd.toDouble())
                    Math.rint(x * factor) / factor
                } else Math.rint(x).toLong()
            }
            g["len"] = NativeFn("len") { a, _ -> Py.len(a[0]) }
            g["int"] = NativeFn("int") { a, _ ->
                if (a.isEmpty()) 0L
                else when (val v = a[0]) {
                    is String -> v.trim().toLong()
                    is Double -> v.toLong()
                    is Long -> v
                    is Boolean -> if (v) 1L else 0L
                    else -> throw PyRuntimeError("int() argument invalid")
                }
            }
            g["float"] = NativeFn("float") { a, _ -> if (a.isEmpty()) 0.0 else Py.toDouble(a[0]) }
            g["str"] = NativeFn("str") { a, _ -> if (a.isEmpty()) "" else Py.str(a[0]) }
            g["bool"] = NativeFn("bool") { a, _ -> if (a.isEmpty()) false else Py.truthy(a[0]) }
            g["list"] = NativeFn("list") { a, _ -> if (a.isEmpty()) PyList() else PyList(Py.iterate(a[0]).toMutableList()) }
            g["tuple"] = NativeFn("tuple") { a, _ -> if (a.isEmpty()) PyTuple(emptyList()) else PyTuple(Py.iterate(a[0])) }
            g["dict"] = NativeFn("dict") { a, kw ->
                val m = LinkedHashMap<Any, Any>()
                if (a.isNotEmpty() && a[0] is PyDict) m.putAll((a[0] as PyDict).map)
                for ((k, v) in kw) m[k] = v
                PyDict(m)
            }
            g["range"] = NativeFn("range") { a, _ -> rangeFn(a) }
            g["enumerate"] = NativeFn("enumerate") { a, _ ->
                val start = if (a.size > 1) Py.toLong(a[1]) else 0L
                val out = ArrayList<Any>()
                var idx = start
                for (it in Py.iterate(a[0])) { out.add(PyTuple(listOf(idx, it))); idx++ }
                PyList(out)
            }
            g["zip"] = NativeFn("zip") { a, _ ->
                val lists = a.map { Py.iterate(it) }
                val n = lists.minOfOrNull { it.size } ?: 0
                val out = ArrayList<Any>()
                for (idx in 0 until n) out.add(PyTuple(lists.map { it[idx] }))
                PyList(out)
            }
            g["map"] = NativeFn("map") { a, _ ->
                val fn = a[0] as PyCallable
                PyList(Py.iterate(a[1]).map { fn.call(listOf(it), emptyMap()) }.toMutableList())
            }
            g["sorted"] = NativeFn("sorted") { a, kw ->
                val items = Py.iterate(a[0]).toMutableList()
                val key = kw["key"]
                val reverse = kw["reverse"]?.let { Py.truthy(it) } ?: false
                val cmp = Comparator<Any> { x, y ->
                    val kx = if (key is PyCallable) key.call(listOf(x), emptyMap()) else x
                    val ky = if (key is PyCallable) key.call(listOf(y), emptyMap()) else y
                    Py.compare(kx, ky)
                }
                items.sortWith(if (reverse) cmp.reversed() else cmp)
                PyList(items)
            }
            g["print"] = NativeFn("print") { a, _ ->
                android.util.Log.i("PylaScript", a.joinToString(" ") { Py.str(it) })
                PyNone
            }
            g["time_now"] = NativeFn("time_now") { _, _ -> System.currentTimeMillis() / 1000.0 }
            g["random_int"] = NativeFn("random_int") { a, _ ->
                val lo = Py.toLong(a[0]); val hi = Py.toLong(a[1]); randInt(rng, lo, hi)
            }
            g["isinstance"] = NativeFn("isinstance") { _, _ -> true } // lenient
            g["type"] = NativeFn("type") { a, _ -> Py.typeName(a[0]) }

            // exceptions
            for (ex in listOf("Exception", "ValueError", "TypeError", "RuntimeError",
                "KeyError", "IndexError", "ZeroDivisionError", "AttributeError", "NameError")) {
                g[ex] = NativeFn(ex) { a, _ -> PyException(ex, if (a.isEmpty()) "" else Py.str(a[0])) }
            }

            g["math"] = mathModule()
            g["random"] = randomModule(rng)
            g["time"] = timeModule()
        }

        private fun randInt(rng: Random, lo: Long, hi: Long): Long {
            if (hi < lo) return lo
            return lo + (rng.nextDouble() * (hi - lo + 1)).toLong().coerceAtMost(hi - lo)
        }

        private fun rangeFn(a: List<Any>): PyList {
            val out = ArrayList<Any>()
            when (a.size) {
                1 -> { var i = 0L; val n = Py.toLong(a[0]); while (i < n) { out.add(i); i++ } }
                2 -> { var i = Py.toLong(a[0]); val n = Py.toLong(a[1]); while (i < n) { out.add(i); i++ } }
                else -> {
                    var i = Py.toLong(a[0]); val n = Py.toLong(a[1]); val step = Py.toLong(a[2])
                    if (step == 0L) throw PyRaiseException(PyException("ValueError", "range() arg 3 must not be zero"))
                    if (step > 0) while (i < n) { out.add(i); i += step } else while (i > n) { out.add(i); i += step }
                }
            }
            return PyList(out)
        }

        private fun minMax(a: List<Any>, kw: Map<String, Any>, isMin: Boolean): Any {
            val items = if (a.size == 1) Py.iterate(a[0]) else a
            if (items.isEmpty()) throw PyRaiseException(PyException("ValueError", "arg is an empty sequence"))
            val key = kw["key"]
            var best = items[0]
            var bestKey = if (key is PyCallable) key.call(listOf(best), emptyMap()) else best
            for (idx in 1 until items.size) {
                val cand = items[idx]
                val candKey = if (key is PyCallable) key.call(listOf(cand), emptyMap()) else cand
                val c = Py.compare(candKey, bestKey)
                if ((isMin && c < 0) || (!isMin && c > 0)) { best = cand; bestKey = candKey }
            }
            return best
        }

        private fun mathModule(): PyModule {
            val m = HashMap<String, Any>()
            m["pi"] = Math.PI
            m["e"] = Math.E
            m["tau"] = Math.PI * 2
            m["inf"] = Double.POSITIVE_INFINITY
            m["nan"] = Double.NaN
            fun fn1(name: String, f: (Double) -> Double) { m[name] = NativeFn("math.$name") { a, _ -> f(Py.toDouble(a[0])) } }
            fn1("sqrt") { Math.sqrt(it) }
            fn1("sin") { Math.sin(it) }
            fn1("cos") { Math.cos(it) }
            fn1("tan") { Math.tan(it) }
            fn1("asin") { Math.asin(it) }
            fn1("acos") { Math.acos(it) }
            fn1("atan") { Math.atan(it) }
            fn1("exp") { Math.exp(it) }
            fn1("fabs") { Math.abs(it) }
            fn1("radians") { Math.toRadians(it) }
            fn1("degrees") { Math.toDegrees(it) }
            m["ceil"] = NativeFn("math.ceil") { a, _ -> Math.ceil(Py.toDouble(a[0])).toLong() }
            m["floor"] = NativeFn("math.floor") { a, _ -> Math.floor(Py.toDouble(a[0])).toLong() }
            m["trunc"] = NativeFn("math.trunc") { a, _ -> Py.toDouble(a[0]).toLong() }
            m["hypot"] = NativeFn("math.hypot") { a, _ -> Math.hypot(Py.toDouble(a[0]), Py.toDouble(a[1])) }
            m["atan2"] = NativeFn("math.atan2") { a, _ -> Math.atan2(Py.toDouble(a[0]), Py.toDouble(a[1])) }
            m["pow"] = NativeFn("math.pow") { a, _ -> Math.pow(Py.toDouble(a[0]), Py.toDouble(a[1])) }
            m["log"] = NativeFn("math.log") { a, _ ->
                if (a.size > 1) Math.log(Py.toDouble(a[0])) / Math.log(Py.toDouble(a[1])) else Math.log(Py.toDouble(a[0]))
            }
            m["log10"] = NativeFn("math.log10") { a, _ -> Math.log10(Py.toDouble(a[0])) }
            m["dist"] = NativeFn("math.dist") { a, _ ->
                val p = Py.seqItems(a[0]); val q = Py.seqItems(a[1])
                var s = 0.0
                for (idx in p.indices) { val d = Py.toDouble(p[idx]) - Py.toDouble(q[idx]); s += d * d }
                Math.sqrt(s)
            }
            return PyModule("math", m)
        }

        private fun randomModule(rng: Random): PyModule {
            val m = HashMap<String, Any>()
            m["random"] = NativeFn("random.random") { _, _ -> rng.nextDouble() }
            m["randint"] = NativeFn("random.randint") { a, _ -> randInt(rng, Py.toLong(a[0]), Py.toLong(a[1])) }
            m["uniform"] = NativeFn("random.uniform") { a, _ ->
                val lo = Py.toDouble(a[0]); val hi = Py.toDouble(a[1]); lo + rng.nextDouble() * (hi - lo)
            }
            m["randrange"] = NativeFn("random.randrange") { a, _ ->
                val lo: Long; val hi: Long
                if (a.size == 1) { lo = 0; hi = Py.toLong(a[0]) - 1 } else { lo = Py.toLong(a[0]); hi = Py.toLong(a[1]) - 1 }
                randInt(rng, lo, hi)
            }
            m["choice"] = NativeFn("random.choice") { a, _ ->
                val items = Py.iterate(a[0])
                if (items.isEmpty()) throw PyRaiseException(PyException("IndexError", "cannot choose from an empty sequence"))
                items[rng.nextInt(items.size)]
            }
            m["shuffle"] = NativeFn("random.shuffle") { a, _ ->
                val l = a[0]
                if (l is PyList) {
                    for (idx in l.items.size - 1 downTo 1) {
                        val j = rng.nextInt(idx + 1)
                        val tmp = l.items[idx]; l.items[idx] = l.items[j]; l.items[j] = tmp
                    }
                }
                PyNone
            }
            m["sample"] = NativeFn("random.sample") { a, _ ->
                val items = Py.iterate(a[0]).toMutableList()
                val k = Py.toLong(a[1]).toInt()
                val out = ArrayList<Any>()
                repeat(minOf(k, items.size)) { out.add(items.removeAt(rng.nextInt(items.size))) }
                PyList(out)
            }
            return PyModule("random", m)
        }

        private fun timeModule(): PyModule {
            val m = HashMap<String, Any>()
            m["time"] = NativeFn("time.time") { _, _ -> System.currentTimeMillis() / 1000.0 }
            m["monotonic"] = NativeFn("time.monotonic") { _, _ -> System.nanoTime() / 1e9 }
            m["sleep"] = NativeFn("time.sleep") { _, _ -> PyNone } // no-op in bot loop
            return PyModule("time", m)
        }
    }
}
