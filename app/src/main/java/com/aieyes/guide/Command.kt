package com.aieyes.guide

sealed class Command {
    data class FindTarget(val classIndex: Int, val spokenName: String) : Command()
    data object Stop : Command()
    data object Repeat : Command()

    /** User says they have found and touched the target; ends the current search. */
    data object Confirm : Command()
    data object Unknown : Command()
}
