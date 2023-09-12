package com.mrsep.musicrecognizer.feature.library.presentation.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrsep.musicrecognizer.core.ui.components.LoadingStub
import com.mrsep.musicrecognizer.core.ui.components.rememberMultiSelectionState
import com.mrsep.musicrecognizer.feature.library.domain.model.TrackFilter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(
    onTrackClick: (mbId: String) -> Unit,
    onTrackSearchClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val screenUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLibraryEmpty = screenUiState !is LibraryUiState.Success
    var filterSheetActive by rememberSaveable { mutableStateOf(false) }
    val multiSelectionState = rememberMultiSelectionState<String>(screenUiState)
    var deleteDialogVisible by rememberSaveable(screenUiState) {
        mutableStateOf(false)
    }
    var deletionInProgress by rememberSaveable(screenUiState) {
        mutableStateOf(false)
    }

    BackHandler(
        enabled = multiSelectionState.multiselectEnabled,
        onBack = multiSelectionState::deselectAll
    )

    val topBarBehaviour = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Column(
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.background),
    ) {
        LibraryScreenTopBar(
            isLibraryEmpty = isLibraryEmpty,
            isFilterApplied = (screenUiState as? LibraryUiState.Success)?.trackFilter?.isEmpty?.not() ?: false,
            isMultiselectEnabled = multiSelectionState.multiselectEnabled,
            selectedCount = multiSelectionState.selectedCount,
            totalCount = (screenUiState as? LibraryUiState.Success)?.trackList?.size ?: 0,
            onSearchIconClick = onTrackSearchClick,
            onFilterIconClick = { filterSheetActive = !filterSheetActive },
            onDeleteIconClick = { deleteDialogVisible = true },
            onSelectAll = {
                (screenUiState as? LibraryUiState.Success)?.trackList?.let { tracks ->
                    multiSelectionState.select(tracks.map { it.mbId })
                }
            },
            onDeselectAll = multiSelectionState::deselectAll,
            topAppBarScrollBehavior = topBarBehaviour
        )
        when (val uiState = screenUiState) {
            LibraryUiState.Loading -> LoadingStub(
                modifier = Modifier.fillMaxSize()
            )

            LibraryUiState.EmptyLibrary -> EmptyLibraryMessage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
            )

            is LibraryUiState.Success -> {
                val lazyGridState = rememberLazyGridState()
                val filterSheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = true
                )
                var newFilterApplied by rememberSaveable { mutableStateOf(false) }

                fun hideFilterSheet(newTrackFilter: TrackFilter? = null) {
                    scope.launch { filterSheetState.hide() }.invokeOnCompletion {
                        if (!filterSheetState.isVisible) filterSheetActive = false
                        newTrackFilter?.let {
                            newFilterApplied = true
                            viewModel.applyFilter(newTrackFilter)
                        }
                    }
                }

                LaunchedEffect(uiState.trackFilter) {
                    if (newFilterApplied) {
                        lazyGridState.animateScrollToItem(0)
                        newFilterApplied = false
                    }
                }

                TrackLazyGrid(
                    trackList = uiState.trackList,
                    onTrackClick = onTrackClick,
                    lazyGridState = lazyGridState,
                    multiSelectionState = multiSelectionState,
                    modifier = Modifier.nestedScroll(
                        topBarBehaviour.nestedScrollConnection
                    )
                )
                if (filterSheetActive) {
                    val filterState = rememberTrackFilterState(
                        initialTrackFilter = uiState.trackFilter
                    )
                    TrackFilterBottomSheet(
                        sheetState = filterSheetState,
                        filterState = filterState,
                        onDismissRequest = { hideFilterSheet() },
                        onApplyClick = {
                            hideFilterSheet(filterState.makeFilter())
                        }
                    )
                }
                if (deleteDialogVisible) {
                    DeleteSelectedDialog(
                        onDeleteClick = {
                            deletionInProgress = true
                            viewModel.deleteByIds(multiSelectionState.getSelected())
                        },
                        onDismissClick = { deleteDialogVisible = false },
                        inProgress = deletionInProgress
                    )
                }
            }
        }
    }
}

@Stable
private fun LibraryUiState.isFilterApplied(): Boolean {
    return when (this) {
        is LibraryUiState.Success -> !trackFilter.isEmpty
        else -> false
    }
}