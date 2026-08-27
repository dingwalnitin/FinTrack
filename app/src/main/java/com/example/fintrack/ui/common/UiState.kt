package com.example.fintrack.ui.common

/**
 * Common sealed UI state: empty/loading/content/error/review.
 * Review is first-class because pending/failed enrichment must be surfaced.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data object Empty : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
    data class Error(val message: String, val retryable: Boolean = true) : UiState<Nothing>
    data class Review<T>(val data: T, val reason: ReviewReason) : UiState<T>
}

enum class ReviewReason { PENDING_ENRICHMENT, FAILED_ENRICHMENT, USER_REVIEW_REQUESTED }
