package com.phoneassistant

/** Converts a prop's contact point to Pip's window coordinates using his painted sole line. */
internal object ToyFooting {
    private const val BOTTOM_PADDING_DP = 2f
    const val WALK_TOP_PADDING_DP = 8f
    const val SHOE_CENTRE_DP = 7.5f
    const val SHOE_WIDTH_DP = 7f

    fun feetY(top: Float, height: Int, density: Float) = top + height - BOTTOM_PADDING_DP * density

    fun topForFeet(feet: Float, height: Int, density: Float) = feet - height + BOTTOM_PADDING_DP * density

    fun centreForFeet(feet: Float, height: Int, density: Float) = topForFeet(feet, height, density) + height / 2f

    fun beside(propX: Float, propWidth: Int, petWidth: Int, stageWidth: Int, gap: Float, preferLeft: Boolean): Float {
        val halfPet = petWidth / 2f
        val min = halfPet
        val max = (stageWidth - halfPet).coerceAtLeast(min)
        val left = propX - gap - halfPet
        val right = propX + propWidth + gap + halfPet
        return when {
            preferLeft && left >= min -> left
            right <= max -> right
            left >= min -> left
            (min - left) <= (right - max) -> min
            else -> max
        }
    }
}
