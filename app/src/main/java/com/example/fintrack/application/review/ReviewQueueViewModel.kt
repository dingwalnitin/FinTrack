package com.example.fintrack.application.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.domain.model.ReviewItem
import com.example.fintrack.domain.service.ReviewQueueService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stage 7 P15 #1: review queue ViewModel.
 *
 * Loads open items once and exposes resolve/dismiss. UI never touches
 * Room directly; all writes go through [ReviewQueueService].
 */
class ReviewQueueViewModel(
    private val service: ReviewQueueService,
) : ViewModel() {

    private val _openItems = MutableStateFlow<List<ReviewItem>>(emptyList())
    val openItems: StateFlow<List<ReviewItem>> = _openItems.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _openItems.value = service.openItems()
        }
    }

    fun resolve(itemId: String) {
        viewModelScope.launch {
            service.resolve(itemId)
            refresh()
        }
    }

    fun dismiss(itemId: String) {
        viewModelScope.launch {
            service.dismiss(itemId)
            refresh()
        }
    }
}
