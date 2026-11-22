package com.phoneassistant

/**
 * What each toy switch in Pip settings means when its information button is tapped.
 *
 * The text is the toy menu's own description, so there is one wording per toy rather than two
 * that drift. The `when` has no `else` on purpose: a new [Toy] will not compile until it has a
 * description here, so a toy cannot quietly lose its explanation. A name that does not exist in
 * `strings.xml` fails the resource build, so a typo cannot reach a phone either.
 */
object ToyNotes {
    /** The string resource for [toy]'s description. */
    fun description(toy: Toy): Int = when (toy) {
        Toy.LEMONADE -> R.string.toy_lemonade_description
        Toy.PAINTING -> R.string.toy_canvas_description
        Toy.SOCCER -> R.string.toy_soccer_description
        Toy.BUBBLES -> R.string.toy_bubbles_description
        Toy.PLANT -> R.string.toy_plant_description
        Toy.DOOMSCROLL -> R.string.toy_doomscroll_description
    }

    /** The description as text, for callers that already have a `getString`. */
    fun text(toy: Toy, get: (Int) -> String): String = get(description(toy))
}
