package com.soywiz.ktcc.gen

import com.soywiz.ktcc.*
import com.soywiz.ktcc.util.*

class KotlinGenerator {
    val analyzer = ProgramAnalyzer()

    fun generate(program: Program) = Indenter {
        analyzer.visit(program)

        for (str in analyzer.strings) {
            line("// $str")
        }

        for (type in analyzer.structTypesByName.values) {
            val typeName = type.name
            val typeFields = type.fields
            line("/*!inline*/ class $typeName(val ptr: Int) {")
            indent {
                line("companion object {")
                indent {
                    val fields = typeFields.map { it.name + ": " + it.type.str() }
                    val fieldsSet = typeFields.map { "this." + it.name + " = " + it.name }
                    line("operator fun invoke(${fields.joinToString(", ")}): $typeName = $typeName(alloca(SIZE)).also { ${fieldsSet.joinToString("; ")} }")
                    line("const val SIZE = ${type.size}")
                    for (field in typeFields) {
                        // OFFSET_
                        line("const val ${field.offsetName} = ${field.offset}")
                    }
                }
                line("}")
                for (field in typeFields) {
                    val ftype = field.type
                    val foffsetName = "$typeName.${field.offsetName}"
                    when (ftype) {
                        is IntFType -> {
                            val ftypeSize = ftype.size ?: 4
                            when (ftypeSize) {
                                4 -> line("var ${field.name}: $ftype get() = lw(ptr + $foffsetName); set(value) = sw(ptr + $foffsetName, value)")
                                else -> line("var ${field.name}: $ftype get() = TODO(\"ftypeSize=$ftypeSize\"); set(value) = TODO()")
                            }
                        }
                        else -> line("var ${field.name}: $ftype get() = CPointer(lw(ptr + $foffsetName)); set(value) = run { sw(ptr + $foffsetName, value.ptr) }")
                    }
                }
            }
            line("}")
        }

        for (decl in program.decls) {
            generate(decl)
        }
    }

    fun Indenter.generate(it: Decl): Unit {
        when (it) {
            is FuncDecl -> {
                line("fun ${it.name.name}(${it.params.map { generateParam(it) }.joinToString(", ")}): ${generate(it.rettype)} = stackFrame {")
                indent {
                    generate(it.body)
                }
                line("}")
            }
            is Declaration -> {
                val ftype = it.specs.toFinalType()
                for (init in it.initDeclaratorList) {
                    if (init.decl is ParameterDeclarator) continue // Do not include empty/external functions

                    val varType = ftype.withDeclarator(init.decl).resolve()
                    val name = init.decl.getName()
                    val varInit = init.initializer
                    if (varInit != null) {
                        line("var $name: ${varType.str()} = ${(varInit).generate(leftType = varType)}")
                    } else {
                        line("var $name: ${varType.str()}")
                    }
                }
            }
            else -> error("Don't know how to generate decl $it")
        }
    }

    fun FType.resolve(): FType = when {
        this is TypedefFTypeRef -> analyzer.typedefAliases[this.id]?.resolve() ?: error("Can't find type with id=$id")
        else -> this
    }

    fun FType.str(): String = when (this) {
        is PointerFType -> "CPointer<${this.type.str()}>"
        is StructFType -> analyzer.getType(this.spec).name
        else -> this.toString()
    }

    fun Indenter.generate(it: Stm): Unit = when (it) {
        is Stms -> {
            for (s in it.stms) generate(s)
        }
        is Return -> {
            if (it.expr != null) line("return ${(it.expr).generate()}") else line("return")
        }
        is ExprStm -> {
            if (it.expr != null) line(it.expr.generate(par = false))
            Unit
        }
        is While -> {
            line("while (${(it.cond).generate()}) {")
            indent {
                generate(it.body)
            }
            line("}")
        }
        is For -> {
            if (it.init != null) {
                val init = it.init
                if (init != null) {
                    if (init !is Decl) error("Not a Decl in for init")
                    generate(init)
                }
            }
            line("while (${(it.cond ?: IntConstant("1")).generate()}) {")
            indent {
                generate(it.body)
                if (it.post != null) {
                    line(it.post.generate())
                }
            }
            line("}")
        }
        is IfElse -> {
            line("if (${it.cond.generate()}) {")
            indent {
                generate(it.strue)
            }
            if (it.sfalse != null) {
                line("} else {")
                indent {
                    generate(it.sfalse)
                }
                line("}")
            } else {
                line("}")
            }
        }
        is Break -> {
            line("break")
        }
        is Decl -> generate(it)
        else -> error("Don't know how to generate stm $it")
    }

    fun generateParam(it: CParam): String = "${it.name}: ${it.type}"

    fun CType.toKotlinType(): String = when (this) {
        is CTypeWithSpecifiers -> {
            var void = false
            var unsigned = false
            var integral = false
            var longCount = 0
            for (spec in specs.items) {
                when (spec) {
                    is BasicTypeSpecifier -> {
                        when (spec.id) {
                            "void" -> void = true
                            "int" -> integral = true
                            else -> TODO(spec.id)
                        }
                    }
                    else -> TODO("toKotlinType")
                }
            }
            when {
                void -> "Unit"
                integral -> "Int"
                else -> TODO("toKotlinType")
            }
        }
        else -> TODO("toKotlinType")
    }

    fun generate(it: CType): String = it.toKotlinType()

    fun generateDefault(it: CType): String = when (it) {
        is NamedCType -> when (it.id.name) {
            "int" -> "0"
            "void" -> "Unit"
            else -> error("Unknown type $it")
        }
        else -> error("Don't know how to generate default value for type $it")
    }

    fun Expr.generate(par: Boolean = true, leftType: FType? = null): String = when (this) {
        is IntConstant -> "$value"
        is Binop -> {
            val base = "${l.generate()} $op ${r.generate()}"
            if (par) "($base)" else base
        }
        is Id -> name
        is PostfixExpr -> {
            val left = lvalue.generate()
            when (op) {
                "++" -> "$left = $left + 1"
                "--" -> "$left = $left - 1"
                else -> TODO("Don't know how to generate postfix operator '$op'")
            }
        }
        is CallExpr -> expr.generate() + "(" + args.joinToString(", ") { it.generate() } + ")"
        is StringConstant -> "$raw.ptr"
        is CharConstant -> "$raw.toInt()"
        is CastExpr -> "${expr.generate()}.to${type.specifiers.toFinalType().withDeclarator(type.abstractDecl)}()"
        is ArrayAccessExpr -> "${expr.generate()}[${index.generate()}]"
        is UnaryExpr -> {
            when (op) {
                "*" -> "${expr.generate()}[0]"
                else -> TODO("Don't know how to generate unary operator '$op'")
            }
        }
        is ArrayInitExpr -> {
            val structType = leftType!!.getProgramType()
            val structName = structType.name
            val inits = LinkedHashMap<String, String>()
            var index = 0
            for (item in this.items) {
                val field = structType.fields[index++]
                inits[field.name] = item.initializer.generate()
            }
            val setFields = structType.fields.associate { it.name to (inits[it.name] ?: it.type.defaultValue()) }
            "$structName(${setFields.map { "${it.key} = ${it.value}" }.joinToString(", ")})"
            //"listOf(" + this.items.joinToString(", ") { it.initializer.generate() } + ")"
        }
        else -> error("Don't know how to generate expr $this")
    }

    fun FType.defaultValue(): String = when (this) {
        is IntFType -> "0"
        is PointerFType -> "CPointer(0)"
        else -> error("Unknown defaultValue for $this")
    }

    fun StructFType.getProgramType() = analyzer.getType(this.spec)
    fun FType.getProgramType() = when (this) {
        is StructFType -> getProgramType()
        is TypedefFTypeRef -> analyzer.getType(this.id)
        else -> error("$this")
    }
}