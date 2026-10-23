package com.biasnil.audioforge.data

/** How many tabs fit in the tab bar, besides More. */
const val TAB_BAR_SIZE = 4

/**
 * The tabs in the tab bar: the user's [tabBar] choice (tab names, in that
 * order, at most [TAB_BAR_SIZE]); before they've chosen (null), the first
 * visible tabs of [all], as earlier versions did.
 */
fun effectiveTabBar(all: List<String>, tabBar: List<String>?, hidden: Set<String>): List<String> =
    (tabBar ?: all.filter { it !in hidden }).filter { it in all }.distinct().take(TAB_BAR_SIZE)

/**
 * Splits the tabs ([all], tab names in their usual order) into the tab bar
 * ([effectiveTabBar]) and More: every other tab that isn't [hidden]. A tab
 * in the bar always shows, even if it was hidden before.
 */
fun splitTabBar(all: List<String>, tabBar: List<String>?, hidden: Set<String>): Pair<List<String>, List<String>> {
    val bar = effectiveTabBar(all, tabBar, hidden)
    return bar to all.filter { it !in bar && it !in hidden }
}

/**
 * Settings -> Tab bar's drag list: the bar's tabs, then null (the line
 * between the tab bar and More), then every other tab -- hidden ones too,
 * so they can be shown again.
 */
fun tabBarEditList(all: List<String>, tabBar: List<String>?, hidden: Set<String>): List<String?> {
    val bar = effectiveTabBar(all, tabBar, hidden)
    return bar + listOf(null) + all.filter { it !in bar }
}

/**
 * The tab bar after the drag list was rearranged: the tabs above the line,
 * at most [TAB_BAR_SIZE] (a fifth one falls back into More). At least one
 * tab always stays, so the bar never ends up with only More.
 */
fun tabBarFromEditList(list: List<String?>, previous: List<String>): List<String> {
    val above = list.takeWhile { it != null }.filterNotNull().take(TAB_BAR_SIZE)
    return above.ifEmpty { previous.take(1) }
}
