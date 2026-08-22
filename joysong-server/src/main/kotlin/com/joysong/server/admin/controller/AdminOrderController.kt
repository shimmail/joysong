package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.Money
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.reconciliation.repository.ReconciliationIssueRepository
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import com.joysong.server.wallet.dto.toDto
import com.joysong.server.wallet.dto.toSummaryDto
import com.joysong.server.identity.service.ManagementAccessService
import org.springframework.security.core.Authentication
import org.springframework.security.access.AccessDeniedException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 管理后台订单与结算控制器
 * 提供订单管理、状态变更、结算列表、医生-机构项目配置等管理接口
 *
 * @author joysong
 * @since 2026-07-30
 */
@RestController
@RequestMapping("/api/admin")
class AdminOrderController(
    private val orderService: OrderService,
    private val orderStatusLogService: OrderStatusLogService,
    private val settlementRepository: SettlementRepository,
    private val settlementAllocationRepository: SettlementAllocationRepository,
    private val walletLedgerEntryRepository: WalletLedgerEntryRepository,
    private val walletRepository: WalletRepository,
    private val reconciliationIssueRepository: ReconciliationIssueRepository,
    private val doctorInstitutionProjectConfigRepository: DoctorInstitutionProjectConfigRepository,
    private val managementAccessService: ManagementAccessService,
    private val splitRatePolicy: OrderSplitRatePolicy
) {

    companion object {
        private val log = LoggerFactory.getLogger(AdminOrderController::class.java)
        private const val DEFAULT_PAGE = 0
        private const val DEFAULT_SIZE = 20
    }

    /** 查询所有订单列表 */
    @GetMapping("/orders")
    fun listOrders(): BaseResponse<*> = BaseResponse.success(orderService.adminListAll())

    /**
     * 修改订单状态
     * 使用 OrderStatusEnum 校验状态转换合法性
     */
    @PutMapping("/orders/{id}/status")
    fun updateOrderStatus(@PathVariable id: String, @RequestBody body: Map<String, String>): BaseResponse<*> {
        val newStatus = body["status"] ?: return BaseResponse.error<Any>("状态不能为空")
        if (OrderStatusEnum.fromValue(newStatus) == null) {
            return BaseResponse.error<Any>("无效的状态值: $newStatus")
        }
        return try {
            val result = orderService.adminUpdateStatus(id, newStatus)
                ?: return BaseResponse.error<Any>("订单不存在")
            BaseResponse.success(result)
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "状态更新失败")
        } catch (e: IllegalStateException) {
            BaseResponse.error<Any>(e.message ?: "订单状态异常")
        }
    }

    /** 管理员跳过核销码手动完成机构核销，仅供测试。 */
    @PostMapping("/orders/{id}/manual-verify")
    fun manualVerify(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        if (!actor.isAdmin) throw AccessDeniedException("只有平台管理员可以手动核销订单")
        return try {
            BaseResponse.success(orderService.adminManualVerify(id, actor.userId))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "手动核销失败")
        } catch (e: IllegalStateException) {
            BaseResponse.error<Any>(e.message ?: "订单状态异常")
        }
    }

    /**
     * 管理员硬删除订单（物理删除）
     * 支持所有状态的订单，使用原生 SQL 绕过 @SQLDelete 执行真正的 DELETE
     */
    @PostMapping("/orders/{id}/hard-delete")
    fun hardDeleteOrder(@PathVariable id: String): BaseResponse<*> {
        return try {
            orderService.adminHardDeleteOrder(id)
            BaseResponse.success(null)
        } catch (e: RuntimeException) {
            BaseResponse.error<Any>(e.message ?: "删除订单失败")
        }
    }

    /** 删除订单（软删除，走 @SQLDelete 逻辑） */
    @DeleteMapping("/orders/{id}")
    fun deleteOrder(@PathVariable id: String): BaseResponse<*> {
        orderService.adminDeleteById(id)
        return BaseResponse.success(null)
    }

    /** 查询指定用户的订单列表 */
    @GetMapping("/users/{id}/orders")
    fun listOrdersByUser(@PathVariable id: String): BaseResponse<*> {
        return BaseResponse.success(orderService.adminFindByUserId(id))
    }

    /** 查询订单状态变更审计日志 */
    @GetMapping("/orders/{id}/status-logs")
    fun getOrderStatusLogs(@PathVariable id: String): BaseResponse<*> {
        return BaseResponse.success(orderStatusLogService.listByOrderId(id))
    }

    /**
     * 结算列表（分页）
     *
     * @param page 页码（从0开始）
     * @param size 每页数量
     */
    @GetMapping("/settlements")
    fun listSettlements(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): BaseResponse<*> {
        val pageRequest = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "createdAt"))
        return BaseResponse.success(settlementRepository.findAll(pageRequest).map { it.toSummaryDto(null) })
    }

    @GetMapping("/settlements/{id}")
    fun getSettlement(@PathVariable id: Long): BaseResponse<*> {
        val settlement = settlementRepository.findById(id).orElse(null)
            ?: return BaseResponse.error<Any>("结算记录不存在", 404)
        return BaseResponse.success(settlement.toSummaryDto(null))
    }

    @GetMapping("/settlements/{id}/allocations")
    fun getSettlementAllocations(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): BaseResponse<*> {
        val settlement = settlementRepository.findById(id).orElse(null)
            ?: return BaseResponse.error<Any>("结算记录不存在", 404)
        val result = settlementAllocationRepository.findAllBySettlementIdOrderByIdAsc(
            id, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100))
        )
        return BaseResponse.success(mapOf(
            "content" to result.content.map { it.toDto(settlement.currency) },
            "totalElements" to result.totalElements,
            "totalPages" to result.totalPages,
            "number" to result.number,
            "size" to result.size
        ))
    }

    @GetMapping("/ledger/{id}")
    fun getLedgerEntry(@PathVariable id: Long): BaseResponse<*> {
        val entry = walletLedgerEntryRepository.findById(id).orElse(null)
            ?: return BaseResponse.error<Any>("账本记录不存在", 404)
        val wallet = walletRepository.findById(entry.walletId).orElse(null)
            ?: return BaseResponse.error<Any>("账本所属钱包不存在", 404)
        return BaseResponse.success(entry.toDto(wallet.currency))
    }

    @GetMapping("/reconciliation-issues")
    fun listReconciliationIssues(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): BaseResponse<*> {
        val result = reconciliationIssueRepository.findAll(
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "lastDetectedAt"))
        )
        return BaseResponse.success(mapOf(
            "content" to result.content.map { it.toDto() },
            "totalElements" to result.totalElements,
            "totalPages" to result.totalPages,
            "number" to result.number,
            "size" to result.size
        ))
    }

    @GetMapping("/reconciliation-issues/{id}")
    fun getReconciliationIssue(@PathVariable id: Long): BaseResponse<*> {
        val issue = reconciliationIssueRepository.findById(id).orElse(null)
            ?: return BaseResponse.error<Any>("对账问题不存在", 404)
        return BaseResponse.success(issue.toDto())
    }

    /** 查询医生-机构项目配置列表 */
    @GetMapping("/doctor-institution-project-configs")
    fun listConfigs(
        authentication: Authentication,
        @RequestParam(required = false) institutionProjectId: String?,
        @RequestParam(required = false) doctorId: String?
    ): BaseResponse<*> {
        val configs = when {
            !institutionProjectId.isNullOrBlank() && !doctorId.isNullOrBlank() -> {
                val config = doctorInstitutionProjectConfigRepository
                    .findByDoctorIdAndInstitutionProjectId(doctorId, institutionProjectId)
                listOfNotNull(config)
            }
            !institutionProjectId.isNullOrBlank() ->
                doctorInstitutionProjectConfigRepository.findByInstitutionProjectId(institutionProjectId)
            else -> doctorInstitutionProjectConfigRepository.findAll()
        }
        val actor = managementAccessService.actor(authentication)
        return BaseResponse.success(if (actor.isAdmin) configs else configs.filter {
            managementAccessService.canManageSplitConfig(actor, it.doctorId, it.institutionProjectId)
        })
    }

    /** 创建或更新医生-机构项目配置（面诊金 + 医美顾问分账比例 + 机构分成比例） */
    @PostMapping("/doctor-institution-project-configs")
    fun upsertConfig(authentication: Authentication, @RequestBody request: UpsertConfigRequest): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        if (!actor.isAdmin) {
            throw AccessDeniedException("医生或机构调整分账时必须提交双方确认提案")
        }
        require(request.doctorId.isNotBlank()) { "医生ID不能为空" }
        require(request.institutionProjectId.isNotBlank()) { "机构项目ID不能为空" }
        request.consultationFee?.let { require(it >= BigDecimal.ZERO) { "面诊金不能为负数" } }
        if (request.institutionRate != null && request.commissionRate != null) {
            splitRatePolicy.resolve(
                institutionRate = request.institutionRate,
                consultantRate = request.commissionRate
            )
        }
        managementAccessService.requireSplitConfig(actor, request.doctorId, request.institutionProjectId)

        val existing = doctorInstitutionProjectConfigRepository
            .findByDoctorIdAndInstitutionProjectId(request.doctorId, request.institutionProjectId)
            ?: doctorInstitutionProjectConfigRepository
                .findByDoctorIdAndInstitutionProjectIdIncludeDeleted(request.doctorId, request.institutionProjectId)
        val consultationFee = request.consultationFee ?: existing?.consultationFee ?: BigDecimal.ZERO
        val commissionRate = request.commissionRate ?: existing?.commissionRate ?: BigDecimal.ZERO
        val institutionRate = request.institutionRate ?: existing?.institutionRate ?: BigDecimal("40.00")
        val medicalListPrice = request.medicalListPrice ?: existing?.medicalListPrice
        require(medicalListPrice != null && medicalListPrice > BigDecimal.ZERO) { "医疗套餐优惠前金额必须大于 0" }
        Money.requireUsdAmount(medicalListPrice)
        val updatesLegacyValues = request.consultationFee != null ||
            request.commissionRate != null || request.institutionRate != null
        if (existing == null || updatesLegacyValues) {
            require(consultationFee >= BigDecimal.ZERO) { "面诊金不能为负数" }
            if (request.institutionRate == null || request.commissionRate == null) {
                splitRatePolicy.resolve(
                    institutionRate = institutionRate,
                    consultantRate = commissionRate
                )
            }
        }

        val saved = if (existing != null) {
            request.consultationFee?.let { existing.consultationFee = it }
            request.commissionRate?.let { existing.commissionRate = it }
            request.institutionRate?.let { existing.institutionRate = it }
            existing.medicalListPrice = medicalListPrice
            existing.deletedAt = null
            existing.updatedAt = LocalDateTime.now()
            doctorInstitutionProjectConfigRepository.save(existing)
        } else {
            val newConfig = DoctorInstitutionProjectConfigEntity(
                doctorId = request.doctorId,
                institutionProjectId = request.institutionProjectId,
                consultationFee = consultationFee,
                commissionRate = commissionRate,
                institutionRate = institutionRate,
                medicalListPrice = medicalListPrice
            )
            doctorInstitutionProjectConfigRepository.save(newConfig)
        }
        log.info("保存医生-机构项目配置[id={}, doctorId={}, ipId={}]", saved.id, saved.doctorId, saved.institutionProjectId)
        return BaseResponse.success(saved)
    }

    /** 删除医生-机构项目配置 */
    @DeleteMapping("/doctor-institution-project-configs/{id}")
    fun deleteConfig(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        if (!actor.isAdmin) throw AccessDeniedException("只有平台管理员可以删除已生效分账配置")
        val existing = doctorInstitutionProjectConfigRepository.findById(id).orElse(null)
            ?: return BaseResponse.error<Any>("配置不存在: $id", 404)
        managementAccessService.requireSplitConfig(actor, existing.doctorId, existing.institutionProjectId)
        doctorInstitutionProjectConfigRepository.deleteById(id)
        log.info("删除医生-机构项目配置[id={}]", id)
        return BaseResponse.success(null)
    }
}

/** 创建/更新医生-机构项目配置请求体 */
data class UpsertConfigRequest(
    val doctorId: String,
    val institutionProjectId: String,
    val consultationFee: BigDecimal? = null,
    val commissionRate: BigDecimal? = null,
    val institutionRate: BigDecimal? = null,
    val medicalListPrice: BigDecimal? = null
)
