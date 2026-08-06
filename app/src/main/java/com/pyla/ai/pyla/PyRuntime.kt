package com.pyla.ai.pyla

import kotlin.math.floor
import kotlin.math.pow

/** Python None singleton. */
object PyNone {
    override fun toString(): String = "None"
}

/** Callable value. Native functions and script functions both implement this. */
fun interface PyCallable {
    fun call(args: List<Any>, kwargs: Map<String, Any>): Any
}

/** A named native function wrapper. */
class NativeFn(val name: String, private val fn: (List<Any>, Map<String, Any>) -> Any) : PyCallable {
    override fun call(args: List<Any>, kwargs: Map<String, Any>): Any = fn(args, kwargs)
    override fun toString(): String = "<native function $name>"
}

class PyList(val items: MutableList<Any> = ArrayList()) {
    override fun toString(): String = "[" + items.joinToString(", ") { Py.repr(it) } + "]"
}

class PyTuple(val items: List<Any>) {
    override fun toString(): String =
        if (items.size == 1) "(" + Py.repr(items[0]) + ",)"
        else "(" + items.joinToString(", ") { Py.repr(it) } + ")"
}

class PyDict(val map: LinkedHashMap<Any, Any> = LinkedHashMap()) {
    override fun toString(): String =
        "{" + map.entries.joinToString(", ") { "${Py.repr(it.key)}: ${Py.repr(it.value)}" } + "}"
}

class PyModule(val name: String, val members: MutableMap<String, Any> = HashMap())

class PyException(val type: String, val message: String) {
    override fun toString(): String = if (message.isEmpty()) type else "$type: $message"
}

/** Thrown by `raise`. */
class PyRaiseException(val value: Any) : RuntimeException(value.toString())

/** A general runtime error inside the interpreter. */
class PyRuntimeError(message: String) : RuntimeException(message)

object Py {

    fun truthy(v: Any): Boolean = when (v) {
        is PyNone -> false
        is Boolean -> v
        is Long -> v != 0L
        is Double -> v != 0.0
        is String -> v.isNotEmpty()
        is PyList -> v.items.isNotEmpty()
        is PyTuple -> v.items.isNotEmpty()
        is PyDict -> v.map.isNotEmpty()
        else -> true
    }

    fun isNumber(v: Any): Boolean = v is Long || v is Double || v is Boolean

    fun toDouble(v: Any): Double = when (v) {
        is Long -> v.toDouble()
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDouble()
        else -> throw PyRuntimeError("cannot convert ${typeName(v)} to float")
    }

    fun toLong(v: Any): Long = when (v) {
        is Long -> v
        is Double -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is String -> v.trim().toLong()
        else -> throw PyRuntimeError("cannot convert ${typeName(v)} to int")
    }

    fun typeName(v: Any): String = when (v) {
        is PyNone -> "NoneType"
        is Boolean -> "bool"
        is Long -> "int"
        is Double -> "float"
        is String -> "str"
        is PyList -> "list"
        is PyTuple -> "tuple"
        is PyDict -> "dict"
        is PyCallable -> "function"
        is PyModule -> "module"
        is PyException -> "exception"
        else -> v.javaClass.simpleName
    }

    fun repr(v: Any): String = when (v) {
        is String -> "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
        else -> str(v)
    }

    fun str(v: Any): String = when (v) {
        is PyNone -> "None"
        is Boolean -> if (v) "True" else "False"
        is Long -> v.toString()
        is Double -> formatDouble(v)
        is String -> v
        is PyList -> v.toString()
        is PyTuple -> v.toString()
        is PyDict -> v.toString()
        else -> v.toString()
    }

    private fun formatDouble(d: Double): String {
        if (d.isNaN()) return "nan"
        if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
        if (d == floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e16) {
            return d.toLong().toString() + ".0"
        }
        return d.toString()
    }

    // ---------- equality / comparison ----------

    fun eq(a: Any, b: Any): Boolean {
        if (a is PyNone || b is PyNone) return a is PyNone && b is PyNone
        if (isNumber(a) && isNumber(b)) return toDouble(a) == toDouble(b)
        if (a is String && b is String) return a == b
        if (a is PyList && b is PyList) return seqEq(a.items, b.items)
        if (a is PyTuple && b is PyTuple) return seqEq(a.items, b.items)
        if (a is PyList && b is PyTuple) return seqEq(a.items, b.items)
        if (a is PyTuple && b is PyList) return seqEq(a.items, b.items)
        if (a is PyDict && b is PyDict) {
            if (a.map.size != b.map.size) return false
            for ((k, v) in a.map) {
                val bv = b.map[k] ?: return false
                if (!eq(v, bv)) return false
            }
            return true
        }
        return a === b
    }

    private fun seqEq(a: List<Any>, b: List<Any>): Boolean {
        if (a.size != b.size) return false
        for (idx in a.indices) if (!eq(a[idx], b[idx])) return false
        return true
    }

    fun compare(a: Any, b: Any): Int {
        if (isNumber(a) && isNumber(b)) return toDouble(a).compareTo(toDouble(b))
        if (a is String && b is String) return a.compareTo(b)
        if ((a is PyList || a is PyTuple) && (b is PyList || b is PyTuple)) {
            val la = seqItems(a); val lb = seqItems(b)
            val n = minOf(la.size, lb.size)
            for (idx in 0 until n) {
                val c = compare(la[idx], lb[idx])
                if (c != 0) return c
            }
            return la.size.compareTo(lb.size)
        }
        throw PyRuntimeError("'<' not supported between ${typeName(a)} and ${typeName(b)}")
    }

    fun seqItems(v: Any): List<Any> = when (v) {
        is PyList -> v.items
        is PyTuple -> v.items
        else -> throw PyRuntimeError("${typeName(v)} is not a sequence")
    }

    // ---------- arithmetic ----------

    fun binaryOp(op: String, a: Any, b: Any): Any {
        when (op) {
            "+" -> {
                if (a is String && b is String) return a + b
                if (a is PyList && b is PyList) return PyList((a.items + b.items).toMutableList())
                if (a is PyTuple && b is PyTuple) return PyTuple(a.items + b.items)
                requireNums(op, a, b)
                return if (bothLong(a, b)) toLong(a) + toLong(b) else toDouble(a) + toDouble(b)
            }
            "-" -> {
                requireNums(op, a, b)
                return if (bothLong(a, b)) toLong(a) - toLong(b) else toDouble(a) - toDouble(b)
            }
            "*" -> {
                if (a is String && b is Long) return a.repeat(maxOf(0, b.toInt()))
                if (a is Long && b is String) return b.repeat(maxOf(0, a.toInt()))
                if (a is PyList && b is Long) return PyList(repeatList(a.items, b.toInt()))
                if (a is Long && b is PyList) return PyList(repeatList(b.items, a.toInt()))
                requireNums(op, a, b)
                return if (bothLong(a, b)) toLong(a) * toLong(b) else toDouble(a) * toDouble(b)
            }
            "/" -> {
                requireNums(op, a, b)
                val db = toDouble(b)
                if (db == 0.0) throw PyRaiseException(PyException("ZeroDivisionError", "division by zero"))
                return toDouble(a) / db
            }
            "//" -> {
                requireNums(op, a, b)
                if (bothLong(a, b)) {
                    val lb = toLong(b)
                    if (lb == 0L) throw PyRaiseException(PyException("ZeroDivisionError", "integer division or modulo by zero"))
                    return Math.floorDiv(toLong(a), lb)
                }
                val db = toDouble(b)
                if (db == 0.0) throw PyRaiseException(PyException("ZeroDivisionError", "float floor division by zero"))
                return floor(toDouble(a) / db)
            }
            "%" -> {
                if (a is String) return formatPercent(a, b)
                requireNums(op, a, b)
                if (bothLong(a, b)) {
                    val lb = toLong(b)
                    if (lb == 0L) throw PyRaiseException(PyException("ZeroDivisionError", "integer division or modulo by zero"))
                    return Math.floorMod(toLong(a), lb)
                }
                val da = toDouble(a); val db = toDouble(b)
                if (db == 0.0) throw PyRaiseException(PyException("ZeroDivisionError", "float modulo"))
                var r = da % db
                if (r != 0.0 && (r < 0) != (db < 0)) r += db
                return r
            }
            "**" -> {
                requireNums(op, a, b)
                if (bothLong(a, b) && toLong(b) >= 0) {
                    var result = 1L
                    val base = toLong(a); var exp = toLong(b)
                    var bb = base
                    while (exp > 0) {
                        if (exp and 1L == 1L) result *= bb
                        bb *= bb
                        exp = exp shr 1
                    }
                    return result
                }
                return toDouble(a).pow(toDouble(b))
            }
        }
        throw PyRuntimeError("unsupported operator $op")
    }

    private fun formatPercent(fmt: String, arg: Any): String {
        // minimal %-formatting used only in messages
        val args = if (arg is PyTuple) arg.items else listOf(arg)
        var idx = 0
        val sb = StringBuilder()
        var i = 0
        while (i < fmt.length) {
            val c = fmt[i]
            if (c == '%' && i + 1 < fmt.length) {
                val n = fmt[i + 1]
                when (n) {
                    '%' -> sb.append('%')
                    's' -> { sb.append(str(args.getOrElse(idx) { "" })); idx++ }
                    'd' -> { sb.append(toLong(args.getOrElse(idx) { 0L })); idx++ }
                    'f' -> { sb.append(toDouble(args.getOrElse(idx) { 0.0 })); idx++ }
                    else -> { sb.append(c); sb.append(n) }
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun repeatList(items: List<Any>, n: Int): MutableList<Any> {
        val out = ArrayList<Any>()
        repeat(maxOf(0, n)) { out.addAll(items) }
        return out
    }

    private fun bothLong(a: Any, b: Any): Boolean =
        (a is Long || a is Boolean) && (b is Long || b is Boolean)

    private fun requireNums(op: String, a: Any, b: Any) {
        if (!isNumber(a) || !isNumber(b))
            throw PyRuntimeError("unsupported operand type(s) for $op: ${typeName(a)} and ${typeName(b)}")
    }

    // ---------- membership / indexing ----------

    fun contains(container: Any, item: Any): Boolean = when (container) {
        is PyList -> container.items.any { eq(it, item) }
        is PyTuple -> container.items.any { eq(it, item) }
        is PyDict -> container.map.keys.any { eq(it, item) }
        is String -> item is String && container.contains(item)
        else -> throw PyRuntimeError("argument of type '${typeName(container)}' is not iterable")
    }

    fun iterate(v: Any): List<Any> = when (v) {
        is PyList -> ArrayList(v.items)
        is PyTuple -> v.items
        is PyDict -> ArrayList(v.map.keys)
        is String -> v.map { it.toString() as Any }
        else -> throw PyRuntimeError("'${typeName(v)}' object is not iterable")
    }

    fun len(v: Any): Long = when (v) {
        is PyList -> v.items.size.toLong()
        is PyTuple -> v.items.size.toLong()
        is PyDict -> v.map.size.toLong()
        is String -> v.length.toLong()
        else -> throw PyRuntimeError("object of type '${typeName(v)}' has no len()")
    }

    fun getIndex(container: Any, index: Any): Any {
        when (container) {
            is PyList -> return container.items[normIndex(index, container.items.size, container)]
            is PyTuple -> return container.items[normIndex(index, container.items.size, container)]
            is String -> {
                val i = normIndex(index, container.length, container)
                return container[i].toString()
            }
            is PyDict -> {
                for ((k, v) in container.map) if (eq(k, index)) return v
                throw PyRaiseException(PyException("KeyError", repr(index)))
            }
            else -> throw PyRuntimeError("'${typeName(container)}' object is not subscriptable")
        }
    }

    private fun normIndex(index: Any, size: Int, container: Any): Int {
        val i = toLong(index).toInt()
        val real = if (i < 0) i + size else i
        if (real < 0 || real >= size)
            throw PyRaiseException(PyException("IndexError", "${typeName(container)} index out of range"))
        return real
    }

    fun setIndex(container: Any, index: Any, value: Any) {
        when (container) {
            is PyList -> container.items[normIndex(index, container.items.size, container)] = value
            is PyDict -> {
                // replace existing key by value-equality, else insert
                val existing = container.map.keys.firstOrNull { eq(it, index) }
                if (existing != null) container.map[existing] = value else container.map[index] = value
            }
            else -> throw PyRuntimeError("'${typeName(container)}' object does not support item assignment")
        }
    }

    fun getSlice(container: Any, lower: Any?, upper: Any?, step: Any?): Any {
        val size = when (container) {
            is PyList -> container.items.size
            is PyTuple -> container.items.size
            is String -> container.length
            else -> throw PyRuntimeError("'${typeName(container)}' is not sliceable")
        }
        val st = if (step == null || step is PyNone) 1 else toLong(step).toInt()
        if (st == 0) throw PyRaiseException(PyException("ValueError", "slice step cannot be zero"))
        var start: Int
        var stop: Int
        if (st > 0) {
            start = if (lower == null || lower is PyNone) 0 else clampSlice(toLong(lower).toInt(), size)
            stop = if (upper == null || upper is PyNone) size else clampSlice(toLong(upper).toInt(), size)
        } else {
            start = if (lower == null || lower is PyNone) size - 1 else clampSliceNeg(toLong(lower).toInt(), size)
            stop = if (upper == null || upper is PyNone) -1 else clampSliceNeg(toLong(upper).toInt(), size)
        }
        val result = ArrayList<Any>()
        if (container is String) {
            val sb = StringBuilder()
            var i = start
            if (st > 0) while (i < stop) { sb.append(container[i]); i += st }
            else while (i > stop) { sb.append(container[i]); i += st }
            return sb.toString()
        }
        val items = seqItems(container)
        var i = start
        if (st > 0) while (i < stop) { result.add(items[i]); i += st }
        else while (i > stop) { result.add(items[i]); i += st }
        return if (container is PyTuple) PyTuple(result) else PyList(result)
    }

    private fun clampSlice(v: Int, size: Int): Int {
        var r = if (v < 0) v + size else v
        if (r < 0) r = 0
        if (r > size) r = size
        return r
    }

    private fun clampSliceNeg(v: Int, size: Int): Int {
        var r = if (v < 0) v + size else v
        if (r < -1) r = -1
        if (r > size - 1) r = size - 1
        return r
    }
}
