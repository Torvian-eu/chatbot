package eu.torvian.chatbot.server.service.core.impl

import arrow.core.raise.Raise
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import kotlinx.serialization.json.Json

/**
 * Judges the instruction lists of the roles that link a row whose effective rule input changes.
 *
 * A shared row's kind and model target are part of every linking role's list validity, but a content
 * write only sees the written role's list. This check rebuilds each linking role's resulting list
 * with the row's new input substituted and rejects the write when any of those lists would break the
 * per-role rules, so no surface can leave a linking role with an invalid list.
 *
 * @receiver The raise scope of the caller.
 * @param linkedRoleIds Roles that link the row and whose resulting lists are judged; an empty list
 *            short-circuits without a query.
 * @param instructionId The row whose content is changing.
 * @param newRuleInput The row's rule input after the change.
 * @param agentRoleInstructionDao DAO for the roles' ordered links.
 * @param instructionDao DAO for the linked rows' content.
 * @param json Codec used to read a stored row's `custom` text.
 * @param onInvalid Factory building the caller's error from the offending roles and the violated rule.
 */
internal suspend fun <E> Raise<E>.validateLinkedRoleInstructionLists(
    linkedRoleIds: List<Long>,
    instructionId: Long,
    newRuleInput: AgentRoleInstructionRules.RuleInput,
    agentRoleInstructionDao: AgentRoleInstructionDao,
    instructionDao: InstructionDao,
    json: Json,
    onInvalid: (roleIds: List<Long>, reason: String) -> E
) {
    if (linkedRoleIds.isEmpty()) return
    val linksByRole = agentRoleInstructionDao.getLinksForRoles(linkedRoleIds)
    // One content read covers every other row of every linking role, so the check stays batched.
    val otherRowIds = linksByRole.values.flatten()
        .map { it.instructionId }
        .filter { it != instructionId }
        .distinct()
    val rowsById = if (otherRowIds.isEmpty()) {
        emptyMap()
    } else {
        instructionDao.getInstructionsByIds(otherRowIds).associateBy { it.id }
    }
    val failures = linkedRoleIds.mapNotNull { roleId ->
        // A link whose row is missing (impossible under enforced foreign keys) is skipped rather than
        // failing the write; the rules only count kinds, so skipping cannot make an illegal list legal.
        val ruleInputs = linksByRole[roleId].orEmpty().mapNotNull { ref ->
            if (ref.instructionId == instructionId) {
                newRuleInput
            } else {
                rowsById[ref.instructionId]?.toRuleInput(json)
            }
        }
        AgentRoleInstructionRules.violationReason(ruleInputs)?.let { reason -> roleId to reason }
    }
    if (failures.isNotEmpty()) {
        raise(onInvalid(failures.map { it.first }.sorted(), failures.first().second))
    }
}