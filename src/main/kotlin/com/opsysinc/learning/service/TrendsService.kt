package com.opsysinc.learning.service

import com.opsysinc.learning.data.TrendData
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.atomic.AtomicInteger


/**
 * Twitter trends service.
 *
 * Created by mkitchin on 4/22/2017.
 */
@Service
class TrendsService(val twitterService: TwitterService,
                    val twitterStreamService: TwitterStreamService,
                    val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(TrendsService::class.java)

    val g20CountryCodes: Set<String> =
            Collections.unmodifiableSet(TreeSet(listOf("AR", "AU", "BR", "CA", "FR", "DE", "IN", "ID", "IT", "JP", "MX", "RU", "SA", "ZA", "KR", "TR", "GB", "US")))

    val largestUsCityNames: Set<String> =
            Collections.unmodifiableSet(TreeSet(listOf("New York", "Los Angeles", "Chicago", "Houston", "Philadelphia", "Phoenix", "San Antonio", "San Diego", "Dallas", "San Jose")))

    @Value("\${links.search.mode}")
    var linksSearchMode: String? = null

    @Value("\${trends.limits.query.places}")
    var maxPlacesQuery: Int? = null

    @Value("\${trends.limits.query.terms}")
    var maxTermsQuery: Int? = null

    @Value("\${trends.limits.tracking.terms}")
    var maxTermsTracking: Int? = null

    @Scheduled(fixedDelay = (1000 * 60 * 15))
    fun pollTrends() {
        logger.info("pollTrends()")

        val twitter = twitterService.getTwitterClient()
        val finalTrends = mutableListOf<TrendData>()

        synchronized(twitterService.getResourceLock("trends", "/trends/available")) {
            // Step 1: Get trending places
            twitterService.checkRateLimiting("trends", "/trends/available", 0L, 1)
            counterService.increment("services.trends.requests.places")
            val availablePlaces = twitter.trends().availableTrends

            val sortedPlaces = availablePlaces.filter {
                it.placeName.equals("supername", true)
                        || it.placeName.equals("country", true)
                        || (it.name != null && largestUsCityNames.contains(it.name))
            }.sortedByDescending {
                it.placeCode
            }
            val finalPlaces = sortedPlaces.take(maxPlacesQuery!!)
            logger.info("pollTrends() - finalPlaces (${finalPlaces.size}): $finalPlaces")

            // Step 2: Get trending terms
            twitterService.checkRateLimiting("trends", "/trends/place", 0L, finalPlaces.size)
            val trendMap = sortedMapOf<String, TrendData>()
            for (location in finalPlaces) {
                counterService.increment("services.trends.requests.trends")
                val trends = twitter.trends().getPlaceTrends(location.woeid)

                logger.info("trends (${trends.trends.size}): $trends")
                val multiplier =
                        if (location.countryCode != null
                                && g20CountryCodes.contains(location.countryCode)) {
                            if (location.name != null
                                    && largestUsCityNames.contains(location.name)) {
                                3
                            } else {
                                2
                            }
                        } else {
                            1
                        }
                val increment =
                        if (location.placeName.equals("supername", true)
                                || location.placeName.equals("country", true)) {
                            (100 * multiplier)
                        } else {
                            (1 * multiplier)
                        }

                trends.trends
                        .map {
                            trendMap.computeIfAbsent(it.name, { key ->
                                TrendData(key, it, AtomicInteger(0))
                            })
                        }
                        .forEach {
                            it.count.addAndGet(increment)
                        }
            }

            val sortedTrends = trendMap.values.sortedByDescending { it.count.get() }
            finalTrends.addAll(sortedTrends.take(maxTermsQuery!!))

            logger.info("pollTrends() - finalTrends: $finalTrends")
        }

        if (finalTrends.isEmpty()) {
            return
        }

        if (linksSearchMode.equals("trends", true)) {
            val twitterStream = twitterStreamService.getTwitterStreamClient()

            synchronized(twitterStream) {
                twitterStream.filter(*finalTrends.map { it.name }.toTypedArray())
            }
        }
    }
}