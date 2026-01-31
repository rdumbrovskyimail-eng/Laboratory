package com.opuside.app.core.util

/**
 * Состояния таймера кеша.
 */
enum class TimerState {
    /** Таймер не запущен (кеш пуст) */
    STOPPED,
    
    /** Таймер работает (кеш активен) */
    RUNNING,
    
    /** Таймер на паузе */
    PAUSED,
    
    /** Время истекло (кеш нужно обновить) */
    EXPIRED
}