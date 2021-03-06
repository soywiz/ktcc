package com.soywiz.ktcc.transform

import com.soywiz.ktcc.parser.*
import com.soywiz.ktcc.types.*

fun Switch.removeFallthrough(ctx: TempContext): Stm {
    // Verify that it doesn't fallthrough!
    if (
        this.bodyCases.all {
            val last = it.stm.lastStm()
            var breakCount = 0
            it.stm.visitAllDescendants {
                if (it is Break) {
                    breakCount++
                }
            }
            //println("breakCount = $breakCount ; last = $last")
            //(last == null) || (last is EmptyStm) || (last is Break && breakCount == 1) || (last is Return && breakCount == 0) || (last is Continue && breakCount == 0)
            (last is Break && breakCount == 1) || (last is Return && breakCount == 0) || (last is Continue && breakCount == 0)
        }
    ) {
        return SwitchWithoutFallthrough(this.subject, Stms(this.bodyCases.map {
            val nstm = it.stm.removeLastStm()
            if (it is CaseStm) CaseStm(it.expr, nstm) else DefaultStm(nstm)
        }))
    }

    return StmBuilder {
        val it = this@removeFallthrough
        val tempVarName = ctx.gen("when", "_case")
        val tempVarType = Type.INT
        val tempVar = Id(tempVarName, null, tempVarType, false)

        STM(Declaration(tempVarType, tempVarName, IntConstant(-1)))
        val filteredStms = it.body.stms.filterIsInstance<DefaultCaseStm>()
        SWITCH_NO_FALLTHROUGH(it.subject) {
            for ((index, stm) in filteredStms.withIndex().sortedBy { if (it.value is CaseStm) -1 else +1 }) {
                when (stm) {
                    is CaseStm -> CASE(stm.expr, ExprStm(SimpleAssignExpr(tempVar, IntConstant(index))))
                    is DefaultStm -> DEFAULT(ExprStm(SimpleAssignExpr(tempVar, IntConstant(index))))
                }
            }
        }
        WHILE(IntConstant(1)) {
            SWITCH_NO_FALLTHROUGH(tempVar) {
                for ((index, stm) in filteredStms.withIndex()) {
                    CASE(IntConstant(index)) {
                        STM(stm.stm)
                        STM(SimpleAssignExpr(tempVar, IntConstant(index + 1)))
                        CONTINUE()
                    }
                }
            }
            BREAK()
        }.apply {
            addScope = false
        }
    }
}

fun SwitchWithoutFallthrough.toIfs(): Stms = TODO()

fun Stms.lastStm(): Stm? = this.stms.lastOrNull()
fun Stms.removeLastStm(): Stms = Stms(this.stms.dropLast(1))
