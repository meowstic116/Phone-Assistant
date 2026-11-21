package com.phoneassistant

/** Skip an unreadable picture, but don't announce anything until a canvas was placed. */
internal object PaintingStart {
    enum class Result { SHOWN, UNREADABLE, NO_ROOM }

    fun <T> tryEnabledPictures(pictures: List<T>, show: (T) -> Result): Result {
        for (picture in pictures) {
            val result = show(picture)
            if (result != Result.UNREADABLE) return result
        }
        return Result.UNREADABLE
    }
}
