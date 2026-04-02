package com.uber.autoaccept.utils

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.logging.ParsedOfferData
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.ParseConfidence
import com.uber.autoaccept.model.UberOffer
import java.util.UUID

class UberOfferParser {
    companion object {
        private const val TAG = "UberOfferParser"
    }
    
    fun parseOfferDetails(rootNode: AccessibilityNodeInfo): UberOffer? {
        return parseByViewId(rootNode)
    }
    
    private fun parseByViewId(rootNode: AccessibilityNodeInfo): UberOffer? {
        try {
            val (pickupAddress, dropoffAddress, confidence) = findAddresses(rootNode)
                ?: run {
                    Log.w(TAG, "출발지/도착지 추출 실패")
                    RemoteLogger.logParseResult(false, null, "출발지/도착지 추출 실패")
                    return null
                }

            val tripDistanceText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_distance_text_view")?.text?.toString()
            val durationText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_duration_text_view")?.text?.toString()

            val tripDistance = DistanceParser.parseDistance(tripDistanceText)
            val estimatedTime = DistanceParser.parseDuration(durationText)
            val customerDistance = extractCustomerDistance(rootNode)

            Log.d(TAG, """
                오퍼 파싱 성공 ($confidence):
                - 출발지: $pickupAddress
                - 도착지: $dropoffAddress
                - 고객 거리: ${customerDistance}km
                - 여행 거리: ${tripDistance}km
            """.trimIndent())

            val offer = UberOffer(
                offerUuid = UUID.randomUUID().toString(),
                pickupLocation = pickupAddress,
                dropoffLocation = dropoffAddress,
                customerDistance = customerDistance,
                tripDistance = tripDistance,
                estimatedFare = 0,
                estimatedTime = estimatedTime,
                acceptButtonBounds = null,
                acceptButtonNode = null,
                parseConfidence = confidence
            )

            RemoteLogger.logParseResult(
                true,
                ParsedOfferData(
                    offerUuid = offer.offerUuid,
                    pickup = offer.pickupLocation,
                    dropoff = offer.dropoffLocation,
                    customerDistance = offer.customerDistance,
                    tripDistance = offer.tripDistance,
                    parseConfidence = offer.parseConfidence.name,
                    acceptButtonFound = offer.acceptButtonNode != null
                ),
                null
            )

            return offer
        } catch (e: Exception) {
            Log.e(TAG, "ViewId 파싱 오류: ${e.message}", e)
            RemoteLogger.logParseResult(false, null, "ViewId 파싱 오류: ${e.message}")
            return null
        }
    }
    
    /** 출발지/도착지 추출 — virtual view 우선 */
    private fun findAddresses(rootNode: AccessibilityNodeInfo): Triple<String, String, ParseConfidence>? {
        // 1순위: virtual view 탐색 (Uber 앱은 getChild()가 아닌 가상 노드 구조 사용)
        // 트리 순서 = 화면 위→아래 = index 0 이 출발지, index 1 이 도착지
        val addressTerms = listOf("동", "로", "길")
        for (term in addressTerms) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(term)
                    ?.filter { node ->
                        val t = node.text?.toString()
                        !t.isNullOrBlank() && t.length > 10  // "동쪽", "로그" 등 단어 제외
                    }
                    ?: emptyList()
                if (nodes.size >= 2) {
                    val pickup = nodes[0].text.toString()
                    val dropoff = nodes[1].text.toString()
                    Log.d(TAG, "주소 추출: virtual view ($term) | 출발: $pickup | 도착: $dropoff")
                    return Triple(pickup, dropoff, ParseConfidence.MEDIUM)
                }
            } catch (e: Exception) {
                Log.w(TAG, "virtual view 탐색 실패($term): ${e.message}")
            }
        }

        // 2순위: 전체 화면 ViewId
        val pickup2 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_pickup_address_text_view")?.text?.toString()
        val dropoff2 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_dropoff_address_text_view")?.text?.toString()
        if (pickup2 != null && dropoff2 != null) {
            Log.d(TAG, "주소 추출: 전체화면 ViewId")
            return Triple(pickup2, dropoff2, ParseConfidence.HIGH)
        }

        // 3순위: 카드 뷰 ViewId
        val pickup3 = AccessibilityHelper.findNodeByViewId(rootNode, "pick_up_address")?.text?.toString()
        val dropoff3 = AccessibilityHelper.findNodeByViewId(rootNode, "drop_off_address")?.text?.toString()
        if (pickup3 != null && dropoff3 != null) {
            Log.d(TAG, "주소 추출: 카드뷰 ViewId")
            return Triple(pickup3, dropoff3, ParseConfidence.MEDIUM)
        }

        // virtual view 진단: findAccessibilityNodeInfosByText로 접근 가능한 모든 노드 탐색
        Log.w(TAG, "=== VIRTUAL_PROBE: pkg=${rootNode.packageName} childCnt=${rootNode.childCount} ===")
        val probeTerms = listOf("특별시", "광역시", "인천", "서울", "경기", "공항", "터미널", "동", "로", "길", "수락", "콜")
        for (term in probeTerms) {
            try {
                val found = rootNode.findAccessibilityNodeInfosByText(term) ?: emptyList()
                found.forEachIndexed { i, node ->
                    val t = node.text?.toString()
                    val d = node.contentDescription?.toString()
                    Log.w(TAG, "VIRTUAL[$term][$i] text='$t' desc='$d' cls=${node.className}")
                }
            } catch (_: Exception) {}
        }
        // getChild 기반 전체 텍스트 덤프
        val allText = AccessibilityHelper.extractAllText(rootNode)
        Log.w(TAG, "=== ADDR_DUMP (${allText.length}chars) ===")
        allText.chunked(500).forEachIndexed { i, chunk -> Log.w(TAG, "ADDR_DUMP[$i]: $chunk") }

        // 4순위: allText(contentDescription 포함)에서 → 기준 직접 추출
        // 오버레이 렌더링 시 주소가 contentDescription에만 있어 1~3순위 실패할 때 대응
        val arrowIdx = allText.indexOf("→")
        if (arrowIdx > 5) {
            val beforeRaw = allText.substring(0, arrowIdx).trimEnd()
            val afterRaw = allText.substring(arrowIdx + 1).trimStart()
            val cityPattern = Regex("(대한민국|서울특별시|부산광역시|인천광역시|대구광역시|광주광역시|대전광역시|울산광역시|세종특별자치시|경기도)")
            val cityMatch = cityPattern.findAll(beforeRaw).lastOrNull()
            val pickup = if (cityMatch != null) beforeRaw.substring(cityMatch.range.first).trim()
                         else beforeRaw.split(Regex("\\s{2,}")).last().trim()
            val dropoff = afterRaw.split(Regex("\\s{2,}")).first().trim()
            if (pickup.length > 5 && dropoff.length > 5) {
                Log.w(TAG, "주소 추출: 4순위 arrow | 출발: $pickup | 도착: $dropoff")
                RemoteLogger.logParseResult(false, null, "ARROW_SUCCESS: $pickup → $dropoff")
                RemoteLogger.flushNow()
                return Triple(pickup, dropoff, ParseConfidence.LOW)
            } else {
                RemoteLogger.logParseResult(false, null, "ARROW_FAIL: pickup='$pickup'(${pickup.length}) dropoff='$dropoff'(${dropoff.length})")
            }
        } else {
            RemoteLogger.logParseResult(false, null, "ARROW_NOT_FOUND: allText=${allText.take(200)}")
        }

        RemoteLogger.logParseResult(false, null, "DUMP:${allText.take(1000)}")
        RemoteLogger.flushNow()
        return null
    }

    private fun extractCustomerDistance(rootNode: AccessibilityNodeInfo): Double {
        val mapLabel = AccessibilityHelper.findNodeByViewId(rootNode, "ub__upfront_offer_map_label")
        if (mapLabel != null) {
            val labelText = mapLabel.text?.toString() ?: ""
            val distance = DistanceParser.parseDistance(labelText)
            if (distance > 0) return distance
        }

        val allText = AccessibilityHelper.extractAllText(rootNode)

        // 0순위: (Xkm) 형태 — "1분 미만(0km) 남음", "11분(3.8km) 남음" 모두 매칭
        val parenPattern = Regex("\\(([\\d.]+)km\\)")
        val parenMatch = parenPattern.find(allText)
        if (parenMatch != null) {
            val d = parenMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (d >= 0) return d  // 0km도 유효 (아주 가까운 거리)
        }

        val patterns = listOf(
            Regex("\\d+분\\(([\\d.]+)km\\)\\s*남음"),          // "11분(3.8km) 남음" ← 실제 UI
            Regex("픽업까지\\s*([\\d.]+)\\s*km"),               // "픽업까지 3.8km"
            Regex("([\\d.]+)\\s*km\\s*away", RegexOption.IGNORE_CASE),
            Regex("([\\d.]+)\\s*km\\s*to\\s*pickup", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(allText)
            if (match != null) {
                val distance = match.groupValues[1].toDoubleOrNull() ?: 0.0
                if (distance > 0) return distance
            }
        }

        return 2.0 // 기본값
    }
    
    private fun findAcceptButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1순위: 텍스트 — virtual view는 isClickable=false이므로 clickable 체크 없이 직접 반환
        val textVariants = listOf("콜 수락", "수락", "확인", "Accept", "ACCEPT")
        for (text in textVariants) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text) ?: continue
                val node = nodes.firstOrNull { it.text?.toString() == text } ?: nodes.firstOrNull()
                if (node != null) {
                    Log.d(TAG, "버튼 발견: text=${node.text} isClickable=${node.isClickable} cls=${node.className}")
                    return node
                }
            } catch (_: Exception) {}
        }

        // 2순위: ViewId — isClickable 무관하게 반환 (performAction은 non-clickable 노드도 동작 가능)
        val viewIds = listOf(
            "uda_details_accept_button",
            "upfront_offer_configurable_details_accept_button",
            "upfront_offer_configurable_details_auditable_accept_button"
        )
        for (viewId in viewIds) {
            val btn = AccessibilityHelper.findNodeByViewId(rootNode, viewId)
            if (btn != null) return btn
        }

        return null
    }
}
