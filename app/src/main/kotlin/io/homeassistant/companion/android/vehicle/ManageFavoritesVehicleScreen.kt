package io.homeassistant.companion.android.vehicle

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.prefs.AutoFavorite
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.util.vehicle.SUPPORTED_DOMAINS_WITH_STRING
import io.homeassistant.companion.android.util.vehicle.getHeaderBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A Car App screen that allows users to manage their automotive favorites when the vehicle is
 * parked. Each entity from the supported domains is displayed with a toggle to add or remove
 * it from the favorites list. Current favorites are sorted to the top.
 *
 * This screen stays fully within the Car App API, making it compliant with Play Store
 * automotive distribution policies.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ManageFavoritesVehicleScreen(
    carContext: CarContext,
    private val serverId: StateFlow<Int>,
    private val allEntities: Flow<Map<String, Entity>>,
    private val prefsRepository: PrefsRepository,
) : BaseVehicleScreen(carContext) {

    private var entities: List<Entity> = emptyList()
    private var favoritesList: List<AutoFavorite> = emptyList()
    private var isLoaded = false
    private var page = 0

    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoritesList = prefsRepository.getAutoFavorites()
                allEntities.collect { entityMap ->
                    val newEntities = entityMap.values
                        .filter { it.domain in SUPPORTED_DOMAINS_WITH_STRING }
                        .sortedWith(
                            compareByDescending<Entity> { entity ->
                                favoritesList.any {
                                    it.serverId == serverId.value && it.entityId == entity.entityId
                                }
                            }.thenBy { it.attributes["friendly_name"]?.toString() ?: it.entityId },
                        )
                    if (newEntities.map { it.entityId } != entities.map { it.entityId }) {
                        page = 0
                    }
                    entities = newEntities
                    isLoaded = true
                    invalidate()
                }
            }
        }
    }

    override fun onDrivingOptimizedChanged(newState: Boolean) {
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val listLimit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

        // Always reserve 2 rows for navigation (Previous + Next) to keep a fixed itemsPerPage
        // across all pages, avoiding index drift when navigating back and forth.
        val itemsPerPage = (listLimit - 2).coerceAtLeast(1)

        val fromIndex = page * itemsPerPage
        val toIndex = minOf(fromIndex + itemsPerPage, entities.size)
        val hasPreviousPage = page > 0
        val hasNextPage = toIndex < entities.size
        val pageEntities = if (isLoaded) entities.subList(fromIndex, toIndex) else emptyList()

        val listBuilder = ItemList.Builder()

        if (hasPreviousPage) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(commonR.string.aa_previous_page))
                    .setOnClickListener {
                        page--
                        invalidate()
                    }
                    .build(),
            )
        }

        pageEntities.forEach { entity ->
            val isFavorite = favoritesList.any {
                it.serverId == serverId.value && it.entityId == entity.entityId
            }
            val friendlyName = entity.attributes["friendly_name"]?.toString() ?: entity.entityId
            val domainLabel = SUPPORTED_DOMAINS_WITH_STRING[entity.domain]
                ?.let { carContext.getString(it) }
                ?: entity.domain

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(friendlyName)
                    .addText(domainLabel)
                    .setEnabled(!isDrivingOptimized)
                    .setToggle(
                        Toggle.Builder { isChecked ->
                            lifecycleScope.launch {
                                val favorite = AutoFavorite(
                                    serverId = serverId.value,
                                    entityId = entity.entityId,
                                )
                                if (isChecked) {
                                    Timber.d("Adding favorite: ${entity.entityId}")
                                    prefsRepository.addAutoFavorite(favorite)
                                } else {
                                    Timber.d("Removing favorite: ${entity.entityId}")
                                    val updated = favoritesList.filterNot { it == favorite }
                                    prefsRepository.setAutoFavorites(updated)
                                }
                                favoritesList = prefsRepository.getAutoFavorites()
                                invalidate()
                            }
                        }
                            .setChecked(isFavorite)
                            .build(),
                    )
                    .build(),
            )
        }

        if (hasNextPage) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(commonR.string.aa_next_page))
                    .setOnClickListener {
                        page++
                        invalidate()
                    }
                    .build(),
            )
        }

        if (isLoaded && entities.isEmpty()) {
            listBuilder.setNoItemsMessage(carContext.getString(commonR.string.no_supported_entities))
        }

        return ListTemplate.Builder()
            .setHeader(carContext.getHeaderBuilder(commonR.string.android_automotive_favorites).build())
            .setLoading(!isLoaded)
            .apply { if (isLoaded) setSingleList(listBuilder.build()) }
            .build()
    }
}
