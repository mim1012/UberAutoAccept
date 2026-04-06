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
        private val DROPOFF_FALLBACK_IDS = listOf(
            "uda_offer_details_title",
            "uda_offer_details_subtitle",
            "leg_dropoff",
            "leg_dropoff_label"
        )
    }
    
    fun parseOfferDetails(rootNode: AccessibilityNodeInfo): UberOffer? {
        return parseByViewId(rootNode)
    }
    
    private fun parseByViewId(rootNode: AccessibilityNodeInfo): UberOffer? {
        // 비오퍼 화면 조기 탈출: 별도 try-catch로 분리 — 예외 발생 시 false negative 방지
        try {
            val nonOfferKeywords = listOf("운행 리스트", "지금은 요청이 없습니다", "목적지 도착", "운행 명세서")
            for (kw in nonOfferKeywords) {
                if (AccessibilityHelper.findNodeByText(rootNode, kw) != null) {
                    RemoteLogger.logParseResult(false, null, "NON_OFFER_SCREEN: '$kw' 감지 → 파싱 중단")
                    return null
                }
            }
        } catch (e: Exception) {
            // 예외 시 안전하게 통과 — 콜을 놓치는 것보다 비오퍼 화면 통과가 낫다
            Log.w(TAG, "NON_OFFER_SCREEN 체크 예외 → 파싱 계속: ${e.message}")
        }

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
                val rawNodes = rootNode.findAccessibilityNodeInfosByText(term) ?: emptyList()
                val nodes = rawNodes.filter { node ->
                    val t = node.text?.toString()
                    !t.isNullOrBlank() && t.length > 10
                }
                if (nodes.size >= 2) {
                    val pickup = nodes[0].text.toString()
                    val dropoff = nodes[1].text.toString()
                    Log.d(TAG, "주소 추출: virtual view ($term) | 출발: $pickup | 도착: $dropoff")
                    return Triple(pickup, dropoff, ParseConfidence.MEDIUM)
                }
                // 진단: 노드 찾았는데 필터링된 경우 기록
                if (rawNodes.isNotEmpty() && nodes.isEmpty()) {
                    val sample = rawNodes.take(3).joinToString("|") {
                        "t=${it.text?.toString()?.take(15)},d=${it.contentDescription?.toString()?.take(15)}"
                    }
                    RemoteLogger.logParseResult(false, null, "1ST_FILTERED: term=$term raw=${rawNodes.size} sample=[$sample]")
                }
            } catch (e: Exception) {
                Log.w(TAG, "virtual view 탐색 실패($term): ${e.message}")
            }
        }

        // 1.5순위: 도시 키워드 직접 추출 (text + contentDescription 모두 검색)
        // findAccessibilityNodeInfosByText는 text만 검색 → contentDescription 전용 노드 누락
        // AccessibilityHelper.findNodesByTextOrDesc로 트리 전체 순회
        try {
            val pickupCandidates = AccessibilityHelper.findNodesByTextOrDesc(rootNode, "특별시")
                .mapNotNull { node ->
                    (node.text?.toString() ?: node.contentDescription?.toString())
                        ?.takeIf { it.length > 10 }
                }
            val dropoffKeywords = listOf("광역시", "경기도", "인천")
            val dropoffCandidates = dropoffKeywords.flatMap { kw ->
                AccessibilityHelper.findNodesByTextOrDesc(rootNode, kw)
                    .mapNotNull { node ->
                        (node.text?.toString() ?: node.contentDescription?.toString())
                            ?.takeIf { it.length > 10 }
                    }
            }.distinct().filterNot { it.contains("특별시") }
            if (pickupCandidates.isNotEmpty() && dropoffCandidates.isNotEmpty()) {
                val pickup = pickupCandidates[0]
                val dropoff = dropoffCandidates[0]
                Log.w(TAG, "주소 추출: 1.5순위 city keyword | 출발: $pickup | 도착: $dropoff")
                RemoteLogger.logParseResult(false, null, "1_5_CTX: pickup=${pickup.take(30)} dropoff=${dropoff.take(30)}")
                return Triple(pickup, dropoff, ParseConfidence.MEDIUM)
            } else {
                RemoteLogger.logParseResult(false, null, "1_5_FAIL: pickup=${pickupCandidates.size} dropoff=${dropoffCandidates.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "1.5순위 탐색 실패: ${e.message}")
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

        // 5순위: 일반 콜 / 가맹 전용 콜 전용 ViewId (upfront offer와 다른 레이아웃 사용)
        val pickup5 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_offer_details_pickup_title")?.text?.toString()
        val dropoff5 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_offer_details_dropoff_title")?.text?.toString()
        if (pickup5 != null && dropoff5 != null) {
            Log.d(TAG, "주소 추출: 일반 콜 ViewId (5순위)")
            RemoteLogger.logParseResult(false, null, "5TH_OK: pickup=${pickup5.take(30)} dropoff=${dropoff5.take(30)}")
            return Triple(pickup5, dropoff5, ParseConfidence.MEDIUM)
        }
        RemoteLogger.logParseResult(false, null, "5TH_FAIL: pickup=${pickup5?.take(30) ?: "null"} dropoff=${dropoff5?.take(30) ?: "null"}")

        // 6순위: 추가 ViewId 조합 시도
        // 6a: leg_ 접두사 (multi-leg / 일반 콜 공통 레이아웃)
        val pickup6a = AccessibilityHelper.findNodeByViewId(rootNode, "leg_pickup_address")?.text?.toString()
        val dropoff6a = AccessibilityHelper.findNodeByViewId(rootNode, "leg_dropoff_address")?.text?.toString()
        if (pickup6a != null && dropoff6a != null) {
            RemoteLogger.logParseResult(false, null, "6TH_OK(leg): pickup=${pickup6a.take(30)} dropoff=${dropoff6a.take(30)}")
            return Triple(pickup6a, dropoff6a, ParseConfidence.MEDIUM)
        }
        // 6b: pickup5 찾았는데 dropoff만 없는 경우 → 대체 dropoff ViewId 시도
        val effectivePickup = pickup5 ?: pickup6a
        if (effectivePickup != null) {
            val dropoffAlt = DROPOFF_FALLBACK_IDS.firstNotNullOfOrNull {
                AccessibilityHelper.findNodeByViewId(rootNode, it)?.text?.toString()
                    ?.takeIf { t -> t.length > 10 }
                    ?.also { t -> RemoteLogger.logParseResult(false, null, "6TH_DROPOFF_HIT(viewId=$it): ${t.take(30)}") }
            }
            if (dropoffAlt != null) {
                RemoteLogger.logParseResult(false, null, "6TH_OK(alt_dropoff): pickup=${effectivePickup.take(30)} dropoff=${dropoffAlt.take(30)}")
                return Triple(effectivePickup, dropoffAlt, ParseConfidence.LOW)
            }
        }
        RemoteLogger.logParseResult(false, null, "6TH_FAIL: p6a=${pickup6a?.take(20) ?: "null"} d6a=${dropoff6a?.take(20) ?: "null"}")

        // virtual view 진단: findAccessibilityNodeInfosByText로 접근 가능한 모든 노드 탐색
        Log.w(TAG, "=== VIRTUAL_PROBE: pkg=${rootNode.packageName} childCnt=${rootNode.childCount} ===")
        val probeTerms = listOf("특별시", "광역시", "인천", "서울", "경기", "공항", "터미널", "동", "로", "길", "수락", "콜")
        val probeSummary = StringBuilder()
        for (term in probeTerms) {
            try {
                val found = rootNode.findAccessibilityNodeInfosByText(term) ?: emptyList()
                found.forEachIndexed { i, node ->
                    val t = node.text?.toString()
                    val d = node.contentDescription?.toString()
                    Log.w(TAG, "VIRTUAL[$term][$i] text='$t' desc='$d' cls=${node.className}")
                    if (i == 0) probeSummary.append("$term:${t?.take(10) ?: "null"}(desc=${d?.take(10) ?: "null"}) ")
                }
            } catch (_: Exception) {}
        }
        // 1~3순위 실패 + VIRTUAL_PROBE 요약 원격 기록 (현장 증거)
        RemoteLogger.logParseResult(false, null, "PROBE: childCnt=${rootNode.childCount} | $probeSummary")

        // getChild 기반 전체 텍스트 덤프
        val allText = AccessibilityHelper.extractAllText(rootNode)
        Log.w(TAG, "=== ADDR_DUMP (${allText.length}chars) ===")
        allText.chunked(500).forEachIndexed { i, chunk -> Log.w(TAG, "ADDR_DUMP[$i]: $chunk") }

        // 4순위: allText(contentDescription 포함)에서 → 기준 직접 추출
        // 오버레이 렌더링 시 주소가 contentDescription에만 있어 1~3순위 실패할 때 대응
        val addrTerms = listOf("시", "구", "동", "로", "길", "공항", "터미널")
        val isAddressLike = { s: String -> addrTerms.any { s.contains(it) } }
        val arrowIdx = allText.indexOf("→")
        if (arrowIdx > 5) {
            val beforeRaw = allText.substring(0, arrowIdx).trimEnd()
            val afterRaw = allText.substring(arrowIdx + 1).trimStart()
            val cityPattern = Regex("(대한민국|서울특별시|부산광역시|인천광역시|대구광역시|광주광역시|대전광역시|울산광역시|세종특별자치시|경기도)")
            val cityMatch = cityPattern.findAll(beforeRaw).lastOrNull()
            val pickup = if (cityMatch != null) beforeRaw.substring(cityMatch.range.first).trim()
                         else beforeRaw.split(Regex("\\s{2,}")).last().trim()
            val dropoff = afterRaw.split(Regex("\\s{2,}")).first().trim()
            val invalidKeywords = listOf("출발", "도착", "→ ", "KST", "수락된 콜", "개발자", "홈", "수입", "메시지", "메뉴")
            val isInvalidAddr = { s: String -> invalidKeywords.any { s.contains(it) } || s.length > 150 }
            if (pickup.length > 5 && dropoff.length > 5 && isAddressLike(pickup) && isAddressLike(dropoff)
                && !isInvalidAddr(pickup) && !isInvalidAddr(dropoff)) {
                Log.w(TAG, "주소 추출: 4순위 arrow | 출발: $pickup | 도착: $dropoff")
                // 성공 로그는 parseByViewId에서 찍힘(confidence=LOW). 여기선 컨텍스트만 기록.
                RemoteLogger.logParseResult(false, null, "ARROW_CTX: allText_snippet=${allText.take(150)}")
                RemoteLogger.flushNow()
                return Triple(pickup, dropoff, ParseConfidence.LOW)
            } else {
                RemoteLogger.logParseResult(false, null, "ARROW_FAIL: pickup='$pickup'(${pickup.length},addr=${isAddressLike(pickup)}) dropoff='$dropoff'(${dropoff.length},addr=${isAddressLike(dropoff)})")
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
