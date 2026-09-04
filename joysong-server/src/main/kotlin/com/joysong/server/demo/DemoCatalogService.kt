package com.joysong.server.demo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

data class DemoCatalogReport(
    val createdRows: Int,
    val managedRows: Int,
)

@Service
@Profile("demo")
class DemoCatalogService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${upload.private-dir:./data/private}") private val privateUploadDirectory: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun apply(loaded: LoadedDemoCatalog, accountPassword: String): DemoCatalogReport {
        require(accountPassword.length in 12..128) {
            "DEMO_ACCOUNT_PASSWORD must contain 12-128 characters"
        }
        lockDemoApply()
        val catalog = loaded.catalog
        val managedRows = managedRowCount(catalog)
        val demoUserIds = catalog.accounts.map(DemoAccount::id)
        val existingDemoUsers = countByIds("users", "id", demoUserIds)
        if (existingDemoUsers > 0) {
            val report = verifyInternal(loaded, accountPassword)
            logger.info("Demo catalog already matches; Apply performed no writes")
            return report.copy(createdRows = 0)
        }

        val adminId = requireAdminBaseline()
        writeCatalog(catalog, accountPassword, adminId)
        verifyInternal(loaded, accountPassword)
        return DemoCatalogReport(createdRows = managedRows, managedRows = managedRows)
    }

    @Transactional(readOnly = true)
    fun verify(loaded: LoadedDemoCatalog, accountPassword: String): DemoCatalogReport {
        require(accountPassword.length in 12..128) {
            "DEMO_ACCOUNT_PASSWORD must contain 12-128 characters"
        }
        return verifyInternal(loaded, accountPassword)
    }

    private fun requireAdminBaseline(): String {
        val adminIds = jdbcTemplate.queryForList(
            """
            SELECT id FROM users
            WHERE role = 'ADMIN' AND account_state = 'ACTIVE' AND deleted_at IS NULL
            """.trimIndent(),
            String::class.java,
        )
        check(adminIds.size == 1) { "Demo database must contain exactly one active bootstrap administrator" }
        assertTotalCount("users", 1)

        val emptyTables = listOf(
            "private_files",
            "identity_applications",
            "identity_application_documents",
            "user_roles",
            "institutions",
            "institution_memberships",
            "doctors",
            "doctor_institutions",
            "wallets",
            "projects",
            "institution_projects",
            "doctor_projects",
            "doctor_institution_project_configs",
            "professional_project_requests",
        )
        emptyTables.forEach { table -> assertTotalCount(table, 0) }
        DISALLOWED_BUSINESS_TABLES.forEach { table -> assertTotalCount(table, 0) }
        return adminIds.single()
    }

    private fun lockDemoApply() {
        val guardKey = jdbcTemplate.queryForObject(
            "SELECT guard_key FROM admin_account_guard WHERE guard_key = 'ACTIVE_ADMIN' FOR UPDATE",
            String::class.java,
        )
        check(guardKey == "ACTIVE_ADMIN") { "Demo apply lock is not initialized" }
    }

    private fun writeCatalog(catalog: DemoCatalog, accountPassword: String, adminId: String) {
        val accountByCode = catalog.accounts.associateBy(DemoAccount::code)
        val institutionByCode = catalog.institutions.associateBy(DemoInstitution::code)
        val doctorByCode = catalog.doctors.associateBy(DemoDoctor::code)
        val projectByCode = catalog.projects.associateBy(DemoProject::code)
        val institutionProjectByCode = catalog.institutionProjects.associateBy(DemoInstitutionProject::code)

        catalog.accounts.forEach { account ->
            jdbcTemplate.update(
                """
                INSERT INTO users
                    (id, phone, password_hash, nickname, bio, role, account_state)
                VALUES (?, ?, ?, ?, ?, 'USER', ?)
                """.trimIndent(),
                account.id,
                account.phone,
                passwordEncoder.encode(accountPassword),
                account.displayName,
                catalog.sharedContent.account.bio,
                account.accountState,
            )
        }

        catalog.accounts.forEach { account ->
            account.identityDocuments.forEach { document ->
                val storageKey = identityStorageKey(account, document)
                ensurePlaceholderFile(storageKey)
                jdbcTemplate.update(
                    """
                    INSERT INTO private_files
                        (id, owner_user_id, purpose, storage_key, original_name, content_type,
                         size_bytes, sha256, status)
                    VALUES (?, ?, ?, ?, ?, 'image/png', ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    document.fileId,
                    account.id,
                    document.documentType,
                    storageKey,
                    catalog.sharedContent.identity.documentOriginalName,
                    PLACEHOLDER_PNG.size,
                    PLACEHOLDER_PNG_SHA256,
                )
            }
        }

        catalog.accounts.forEach { account ->
            jdbcTemplate.update(
                """
                INSERT INTO identity_applications
                    (id, user_id, role_code, status, application_data, review_note,
                     reviewed_by, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
                account.identityApplicationId,
                account.id,
                account.roleCode,
                account.identityStatus,
                identityDataJson(account),
                identityReviewNote(catalog, account.roleCode),
                adminId,
            )
            account.identityDocuments.forEach { document ->
                jdbcTemplate.update(
                    """
                    INSERT INTO identity_application_documents
                        (application_id, file_id, document_type)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                    account.identityApplicationId,
                    document.fileId,
                    document.documentType,
                )
            }
            jdbcTemplate.update(
                """
                INSERT INTO user_roles
                    (user_id, role_code, status, source_application_id)
                VALUES (?, ?, 'ACTIVE', ?)
                """.trimIndent(),
                account.id,
                account.roleCode,
                account.identityApplicationId,
            )
        }

        catalog.institutions.forEach { institution ->
            val shared = catalog.sharedContent.institution
            val projectCount = catalog.institutionProjects.count { it.institutionCode == institution.code }
            val doctorCount = catalog.doctors.count { it.institutionCode == institution.code }
            jdbcTemplate.update(
                """
                INSERT INTO institutions
                    (id, name, address, city, description, cover_image, images, rating,
                     review_count, is_verified, certification_time, credentials, credential_images, specialties,
                     tags, contact_phone, business_hours, project_count, doctor_count,
                     consultation_count, user_count, case_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                institution.id,
                institution.name,
                institution.address,
                institution.city,
                institution.description,
                shared.coverImage,
                shared.images,
                shared.rating,
                shared.reviewCount,
                shared.isVerified,
                shared.credentials,
                shared.credentialImages,
                shared.specialties,
                shared.tags,
                institution.contactPhone,
                shared.businessHours,
                projectCount,
                doctorCount,
                shared.consultationCount,
                shared.userCount,
                shared.caseCount,
            )
        }

        catalog.legalRepresentativeMemberships.forEach { membership ->
            insertMembership(
                membership.id,
                accountByCode.getValue(membership.accountCode).id,
                institutionByCode.getValue(membership.institutionCode).id,
                "INSTITUTION_LEGAL_REPRESENTATIVE",
                catalog.sharedContent.membership.legalRequestNote,
                catalog.sharedContent.membership.legalReviewNote,
                adminId,
            )
        }

        catalog.consultantMemberships.forEach { membership ->
            insertMembership(
                membership.id,
                accountByCode.getValue(membership.accountCode).id,
                institutionByCode.getValue(membership.institutionCode).id,
                "CONSULTANT",
                catalog.sharedContent.membership.consultantRequestNote,
                catalog.sharedContent.membership.consultantReviewNote,
                adminId,
            )
        }

        catalog.doctors.forEach { doctor ->
            val account = accountByCode.getValue(doctor.accountCode)
            val institution = institutionByCode.getValue(doctor.institutionCode)
            val shared = catalog.sharedContent.doctor
            jdbcTemplate.update(
                """
                INSERT INTO doctors
                    (id, name, title, bio, avatar, institution_id, institution_name, rating,
                     review_count, specialties, is_verified, consultation_count, case_count,
                     credentials, credential_images, certification_tags, contact_phone)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                account.id,
                doctor.name,
                doctor.title,
                doctor.introduction,
                shared.avatar,
                institution.id,
                institution.name,
                shared.rating,
                shared.reviewCount,
                doctor.specialty,
                shared.isVerified,
                shared.consultationCount,
                shared.caseCount,
                shared.credentials,
                shared.credentialImages,
                shared.certificationTags,
                shared.contactPhone,
            )
            jdbcTemplate.update(
                """
                INSERT INTO doctor_institutions
                    (id, doctor_id, institution_id, is_primary, status, request_note,
                     review_note, confirmed_by, confirmed_at)
                VALUES (?, ?, ?, TRUE, 'APPROVED', ?, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
                doctor.practiceRelationId,
                account.id,
                institution.id,
                catalog.sharedContent.membership.doctorPracticeRequestNote,
                catalog.sharedContent.membership.doctorPracticeReviewNote,
                adminId,
            )
        }

        catalog.wallets.forEach { wallet ->
            val ownerId = when (wallet.ownerType) {
                "INSTITUTION" -> institutionByCode.getValue(wallet.ownerCode).id
                "DOCTOR" -> accountByCode.getValue(doctorByCode.getValue(wallet.ownerCode).accountCode).id
                "CONSULTANT" -> accountByCode.getValue(wallet.ownerCode).id
                else -> error("Unsupported demo wallet owner type")
            }
            jdbcTemplate.update(
                """
                INSERT INTO wallets
                    (owner_type, owner_id, currency, pending_minor, available_minor, frozen_minor)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                wallet.ownerType,
                ownerId,
                wallet.currency,
                wallet.pendingMinor,
                wallet.availableMinor,
                wallet.frozenMinor,
            )
        }

        catalog.projects.forEach { project ->
            val shared = catalog.sharedContent.project
            jdbcTemplate.update(
                """
                INSERT INTO projects
                    (id, name, cover_image, category, description, rating, review_count,
                     case_count, tags, images, sales_count, category_tags, reference_price,
                     currency, slogan, detail_content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                project.id,
                project.name,
                shared.coverImage,
                project.category,
                project.description,
                shared.rating,
                project.reviewCount,
                project.caseCount,
                encodeList(project.tags),
                shared.images,
                project.salesCount,
                encodeList(project.categoryTags),
                project.referencePrice,
                project.currency,
                project.slogan,
                project.detailContent,
            )
        }

        catalog.institutionProjects.forEach { project ->
            val shared = catalog.sharedContent.institutionProject
            jdbcTemplate.update(
                """
                INSERT INTO institution_projects
                    (id, institution_id, project_id, name, category, description, rating,
                     review_count, case_count, tags, slogan, detail_content, price,
                     original_price, currency, cover_image, images, sales_count, is_active)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                project.id,
                institutionByCode.getValue(project.institutionCode).id,
                projectByCode.getValue(project.projectCode).id,
                project.name,
                project.description,
                shared.rating,
                project.reviewCount,
                project.caseCount,
                encodeList(project.tags),
                project.slogan,
                project.detailContent,
                project.price,
                project.originalPrice,
                project.currency,
                shared.coverImage,
                shared.images,
                project.salesCount,
                project.isActive,
            )
        }

        catalog.doctorProjects.forEach { project ->
            val doctor = doctorByCode.getValue(project.doctorCode)
            val doctorId = accountByCode.getValue(doctor.accountCode).id
            val institutionProject = institutionProjectByCode.getValue(project.institutionProjectCode)
            val platformProjectId = projectByCode.getValue(institutionProject.projectCode).id
            jdbcTemplate.update(
                """
                INSERT INTO doctor_projects
                    (doctor_id, project_id, institution_project_id, price, service_description,
                     service_tags, schedule_note, cover_image, images, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, '', '', ?)
                """.trimIndent(),
                doctorId,
                platformProjectId,
                institutionProject.id,
                project.price,
                project.description,
                encodeList(project.serviceTags),
                project.scheduleNote,
                project.isActive,
            )
            jdbcTemplate.update(
                """
                INSERT INTO doctor_institution_project_configs
                    (id, doctor_id, institution_project_id, medical_list_price,
                     consultation_fee, commission_rate, institution_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                project.splitConfigId,
                doctorId,
                institutionProject.id,
                project.medicalListPrice,
                project.consultationFee,
                project.commissionRate,
                project.institutionRate,
            )
        }

        catalog.projectRequests.forEach { request ->
            val doctor = doctorByCode.getValue(request.doctorCode)
            val rejected = request.status == "REJECTED"
            jdbcTemplate.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, name, category,
                     description, tags, slogan, detail_content, currency, cover_image, images,
                     sales_count, reference_price, category_tags, price, original_price,
                     is_active, consultation_fee, commission_rate, institution_rate, notes,
                     status, review_note, reviewed_by, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                request.id,
                request.requestType,
                accountByCode.getValue(doctor.accountCode).id,
                request.institutionCode?.let { institutionByCode.getValue(it).id },
                request.projectCode?.let { projectByCode.getValue(it).id },
                request.proposedName,
                request.category,
                request.description,
                encodeList(request.tags),
                request.slogan,
                request.detailContent,
                request.currency,
                catalog.sharedContent.projectRequest.coverImage,
                encodeList(catalog.sharedContent.projectRequest.images),
                catalog.sharedContent.projectRequest.salesCount,
                request.referencePrice,
                request.categoryTags?.let(::encodeList),
                request.price,
                request.originalPrice,
                request.isActive,
                request.consultationFee,
                request.commissionRate,
                request.institutionRate,
                request.reason,
                request.status,
                request.reviewNote,
                if (rejected) adminId else null,
                if (rejected) java.sql.Timestamp(System.currentTimeMillis()) else null,
            )
        }
    }

    private fun insertMembership(
        id: String,
        userId: String,
        institutionId: String,
        role: String,
        requestNote: String,
        reviewNote: String,
        adminId: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, request_note,
                 review_note, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
            id,
            userId,
            institutionId,
            role,
            requestNote,
            reviewNote,
            adminId,
        )
    }

    private fun verifyInternal(loaded: LoadedDemoCatalog, accountPassword: String): DemoCatalogReport {
        val catalog = loaded.catalog
        val adminId = requireSingleAdminForVerify()
        val accountByCode = catalog.accounts.associateBy(DemoAccount::code)
        val institutionByCode = catalog.institutions.associateBy(DemoInstitution::code)
        val doctorByCode = catalog.doctors.associateBy(DemoDoctor::code)
        val projectByCode = catalog.projects.associateBy(DemoProject::code)
        val institutionProjectByCode = catalog.institutionProjects.associateBy(DemoInstitutionProject::code)

        assertTotalCount("users", catalog.accounts.size + 1)
        assertRows(
            "users",
            """
            SELECT id, phone, nickname, bio, role, account_state, deleted_at IS NULL
            FROM users WHERE id IN (${placeholders(catalog.accounts.size)}) ORDER BY id
            """.trimIndent(),
            catalog.accounts.map(DemoAccount::id).toTypedArray(),
            catalog.accounts.sortedBy(DemoAccount::id).map { account ->
                listOf(account.id, account.phone, account.displayName, catalog.sharedContent.account.bio,
                    "USER", account.accountState, 1)
            },
        )
        catalog.accounts.forEach { account ->
            val hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String::class.java,
                account.id,
            ).orEmpty()
            check(passwordEncoder.matches(accountPassword, hash)) {
                "Demo account password drift for ${account.code}"
            }
        }

        val documents = catalog.accounts.flatMap { account ->
            account.identityDocuments.map { document -> account to document }
        }
        assertTotalCount("private_files", documents.size)
        assertRows(
            "private_files",
            """
            SELECT id, owner_user_id, purpose, storage_key, original_name, content_type,
                   size_bytes, sha256, status, deleted_at IS NULL
            FROM private_files ORDER BY id
            """.trimIndent(),
            emptyArray(),
            documents.sortedBy { it.second.fileId }.map { (account, document) ->
                listOf(document.fileId, account.id, document.documentType,
                    identityStorageKey(account, document), catalog.sharedContent.identity.documentOriginalName,
                    "image/png", PLACEHOLDER_PNG.size, PLACEHOLDER_PNG_SHA256, "ACTIVE", 1)
            },
        )
        documents.forEach { (account, document) -> verifyPlaceholderFile(identityStorageKey(account, document)) }

        assertTotalCount("identity_applications", catalog.accounts.size)
        assertRows(
            "identity_applications",
            """
            SELECT id, user_id, role_code, status, CAST(application_data AS CHAR), review_note,
                   reviewed_by, reviewed_at IS NOT NULL
            FROM identity_applications ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.accounts.sortedBy(DemoAccount::identityApplicationId).map { account ->
                listOf(account.identityApplicationId, account.id, account.roleCode, account.identityStatus,
                    identityDataJson(account), identityReviewNote(catalog, account.roleCode), adminId, 1)
            },
        )
        assertTotalCount("identity_application_documents", documents.size)
        assertRows(
            "identity_application_documents",
            "SELECT application_id, file_id, document_type FROM identity_application_documents ORDER BY application_id, file_id",
            emptyArray(),
            documents.sortedWith(compareBy({ it.first.identityApplicationId }, { it.second.fileId })).map { (account, document) ->
                listOf(account.identityApplicationId, document.fileId, document.documentType)
            },
        )
        assertTotalCount("user_roles", catalog.accounts.size)
        assertRows(
            "user_roles",
            """
            SELECT user_id, role_code, status, source_application_id, revoked_at IS NULL
            FROM user_roles ORDER BY user_id, role_code
            """.trimIndent(),
            emptyArray(),
            catalog.accounts.sortedBy(DemoAccount::id).map { account ->
                listOf(account.id, account.roleCode, "ACTIVE", account.identityApplicationId, 1)
            },
        )

        val sharedInstitution = catalog.sharedContent.institution
        assertTotalCount("institutions", catalog.institutions.size)
        assertRows(
            "institutions",
            """
            SELECT id, name, address, city, description, cover_image, images, rating,
                   review_count, is_verified, certification_time IS NOT NULL, credentials,
                   credential_images, specialties, tags,
                   contact_phone, business_hours, project_count, doctor_count,
                   consultation_count, user_count, case_count, deleted_at IS NULL
            FROM institutions ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.institutions.sortedBy(DemoInstitution::id).map { institution ->
                listOf(
                    institution.id, institution.name, institution.address, institution.city,
                    institution.description, sharedInstitution.coverImage, sharedInstitution.images,
                    sharedInstitution.rating, sharedInstitution.reviewCount, sharedInstitution.isVerified,
                    1, sharedInstitution.credentials, sharedInstitution.credentialImages,
                    sharedInstitution.specialties, sharedInstitution.tags, institution.contactPhone,
                    sharedInstitution.businessHours,
                    catalog.institutionProjects.count { it.institutionCode == institution.code },
                    catalog.doctors.count { it.institutionCode == institution.code },
                    sharedInstitution.consultationCount, sharedInstitution.userCount,
                    sharedInstitution.caseCount, 1,
                )
            },
        )

        val membershipExpected = buildList {
            catalog.legalRepresentativeMemberships.forEach { membership ->
                add(listOf(membership.id, accountByCode.getValue(membership.accountCode).id,
                    institutionByCode.getValue(membership.institutionCode).id,
                    "INSTITUTION_LEGAL_REPRESENTATIVE", "APPROVED",
                    catalog.sharedContent.membership.legalRequestNote,
                    catalog.sharedContent.membership.legalReviewNote, adminId, 1, 1))
            }
            catalog.consultantMemberships.forEach { membership ->
                add(listOf(membership.id, accountByCode.getValue(membership.accountCode).id,
                    institutionByCode.getValue(membership.institutionCode).id, "CONSULTANT", "APPROVED",
                    catalog.sharedContent.membership.consultantRequestNote,
                    catalog.sharedContent.membership.consultantReviewNote, adminId, 1, 1))
            }
        }.sortedBy { it.first().toString() }
        assertTotalCount("institution_memberships", membershipExpected.size)
        assertRows(
            "institution_memberships",
            """
            SELECT id, user_id, institution_id, member_role, status, request_note, review_note,
                   confirmed_by, confirmed_at IS NOT NULL, revoked_at IS NULL
            FROM institution_memberships ORDER BY id
            """.trimIndent(),
            emptyArray(),
            membershipExpected,
        )

        val sharedDoctor = catalog.sharedContent.doctor
        assertTotalCount("doctors", catalog.doctors.size)
        assertRows(
            "doctors",
            """
            SELECT id, name, title, bio, avatar, institution_id, institution_name, rating,
                   review_count, specialties, is_verified, consultation_count, case_count,
                   credentials, credential_images, certification_tags, contact_phone,
                   deleted_at IS NULL
            FROM doctors ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.doctors.map { doctor ->
                val account = accountByCode.getValue(doctor.accountCode)
                val institution = institutionByCode.getValue(doctor.institutionCode)
                listOf(account.id, doctor.name, doctor.title, doctor.introduction, sharedDoctor.avatar,
                    institution.id, institution.name, sharedDoctor.rating, sharedDoctor.reviewCount,
                    doctor.specialty, sharedDoctor.isVerified, sharedDoctor.consultationCount,
                    sharedDoctor.caseCount, sharedDoctor.credentials, sharedDoctor.credentialImages,
                    sharedDoctor.certificationTags, sharedDoctor.contactPhone, 1)
            }.sortedBy { it.first().toString() },
        )
        assertTotalCount("doctor_institutions", catalog.doctors.size)
        assertRows(
            "doctor_institutions",
            """
            SELECT id, doctor_id, institution_id, is_primary, status, request_note, review_note,
                   confirmed_by, confirmed_at IS NOT NULL, revoked_at IS NULL, deleted_at IS NULL
            FROM doctor_institutions ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.doctors.sortedBy(DemoDoctor::practiceRelationId).map { doctor ->
                listOf(doctor.practiceRelationId, accountByCode.getValue(doctor.accountCode).id,
                    institutionByCode.getValue(doctor.institutionCode).id, 1, "APPROVED",
                    catalog.sharedContent.membership.doctorPracticeRequestNote,
                    catalog.sharedContent.membership.doctorPracticeReviewNote, adminId, 1, 1, 1)
            },
        )

        assertTotalCount("wallets", catalog.wallets.size)
        assertRows(
            "wallets",
            """
            SELECT owner_type, owner_id, currency, pending_minor, available_minor, frozen_minor
            FROM wallets ORDER BY owner_type, owner_id, currency
            """.trimIndent(),
            emptyArray(),
            catalog.wallets.map { wallet ->
                val ownerId = when (wallet.ownerType) {
                    "INSTITUTION" -> institutionByCode.getValue(wallet.ownerCode).id
                    "DOCTOR" -> accountByCode.getValue(doctorByCode.getValue(wallet.ownerCode).accountCode).id
                    "CONSULTANT" -> accountByCode.getValue(wallet.ownerCode).id
                    else -> error("Unsupported demo wallet owner type")
                }
                listOf(wallet.ownerType, ownerId, wallet.currency, wallet.pendingMinor,
                    wallet.availableMinor, wallet.frozenMinor)
            }.sortedWith(compareBy({ it[0].toString() }, { it[1].toString() }, { it[2].toString() })),
        )

        val sharedProject = catalog.sharedContent.project
        assertTotalCount("projects", catalog.projects.size)
        assertRows(
            "projects",
            """
            SELECT id, name, cover_image, category, description, rating, review_count, case_count,
                   tags, images, sales_count, category_tags, reference_price, currency, slogan,
                   detail_content, deleted_at IS NULL
            FROM projects ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.projects.sortedBy(DemoProject::id).map { project ->
                listOf(project.id, project.name, sharedProject.coverImage, project.category,
                    project.description, sharedProject.rating, project.reviewCount, project.caseCount,
                    encodeList(project.tags), sharedProject.images, project.salesCount,
                    encodeList(project.categoryTags), project.referencePrice, project.currency,
                    project.slogan, project.detailContent, 1)
            },
        )

        val sharedInstitutionProject = catalog.sharedContent.institutionProject
        assertTotalCount("institution_projects", catalog.institutionProjects.size)
        assertRows(
            "institution_projects",
            """
            SELECT id, institution_id, project_id, name, category, description, rating,
                   review_count, case_count, tags, slogan, detail_content, price, original_price,
                   currency, cover_image, images, sales_count, is_active, version, deleted_at IS NULL
            FROM institution_projects ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.institutionProjects.sortedBy(DemoInstitutionProject::id).map { project ->
                listOf(project.id, institutionByCode.getValue(project.institutionCode).id,
                    projectByCode.getValue(project.projectCode).id, project.name, null,
                    project.description, sharedInstitutionProject.rating, project.reviewCount,
                    project.caseCount, encodeList(project.tags), project.slogan, project.detailContent,
                    project.price, project.originalPrice, project.currency,
                    sharedInstitutionProject.coverImage, sharedInstitutionProject.images,
                    project.salesCount, project.isActive, 0, 1)
            },
        )

        assertTotalCount("doctor_projects", catalog.doctorProjects.size)
        assertRows(
            "doctor_projects",
            """
            SELECT doctor_id, project_id, institution_project_id, price, service_description,
                   service_tags, schedule_note, cover_image, images, is_active
            FROM doctor_projects ORDER BY doctor_id, institution_project_id
            """.trimIndent(),
            emptyArray(),
            catalog.doctorProjects.map { project ->
                val doctor = doctorByCode.getValue(project.doctorCode)
                val institutionProject = institutionProjectByCode.getValue(project.institutionProjectCode)
                listOf(accountByCode.getValue(doctor.accountCode).id,
                    projectByCode.getValue(institutionProject.projectCode).id,
                    institutionProject.id, project.price, project.description,
                    encodeList(project.serviceTags), project.scheduleNote, "", "", project.isActive)
            }.sortedWith(compareBy({ it[0].toString() }, { it[2].toString() })),
        )
        assertTotalCount("doctor_institution_project_configs", catalog.doctorProjects.size)
        assertRows(
            "doctor_institution_project_configs",
            """
            SELECT id, doctor_id, institution_project_id, medical_list_price, consultation_fee,
                   commission_rate, institution_rate, deleted_at IS NULL
            FROM doctor_institution_project_configs ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.doctorProjects.sortedBy(DemoDoctorProject::splitConfigId).map { project ->
                val doctor = doctorByCode.getValue(project.doctorCode)
                listOf(project.splitConfigId, accountByCode.getValue(doctor.accountCode).id,
                    institutionProjectByCode.getValue(project.institutionProjectCode).id,
                    project.medicalListPrice, project.consultationFee, project.commissionRate,
                    project.institutionRate, 1)
            },
        )

        assertTotalCount("professional_project_requests", catalog.projectRequests.size)
        assertRows(
            "professional_project_requests",
            """
            SELECT id, request_type, doctor_id, institution_id, project_id, name, category,
                   description, CAST(tags AS CHAR), slogan, detail_content, currency, cover_image,
                   CAST(images AS CHAR), sales_count, reference_price, CAST(category_tags AS CHAR),
                   price, original_price, is_active, consultation_fee, commission_rate,
                   institution_rate, notes, status, review_note, reviewed_by,
                   reviewed_at IS NOT NULL
            FROM professional_project_requests ORDER BY id
            """.trimIndent(),
            emptyArray(),
            catalog.projectRequests.sortedBy(DemoProjectRequest::id).map { request ->
                val doctor = doctorByCode.getValue(request.doctorCode)
                val rejected = request.status == "REJECTED"
                listOf(request.id, request.requestType, accountByCode.getValue(doctor.accountCode).id,
                    request.institutionCode?.let { institutionByCode.getValue(it).id },
                    request.projectCode?.let { projectByCode.getValue(it).id }, request.proposedName,
                    request.category, request.description, encodeList(request.tags), request.slogan,
                    request.detailContent, request.currency,
                    catalog.sharedContent.projectRequest.coverImage,
                    encodeList(catalog.sharedContent.projectRequest.images),
                    catalog.sharedContent.projectRequest.salesCount, request.referencePrice,
                    request.categoryTags?.let(::encodeList), request.price, request.originalPrice,
                    request.isActive, request.consultationFee, request.commissionRate,
                    request.institutionRate, request.reason, request.status, request.reviewNote,
                    if (rejected) adminId else null, if (rejected) 1 else 0)
            },
        )

        DISALLOWED_BUSINESS_TABLES.forEach { assertTotalCount(it, 0) }
        val managedRows = managedRowCount(catalog)
        return DemoCatalogReport(createdRows = 0, managedRows = managedRows)
    }

    private fun requireSingleAdminForVerify(): String {
        val rows = jdbcTemplate.queryForList(
            """
            SELECT id FROM users
            WHERE role = 'ADMIN' AND account_state = 'ACTIVE' AND deleted_at IS NULL
            """.trimIndent(),
            String::class.java,
        )
        check(rows.size == 1) { "Demo database must contain exactly one active bootstrap administrator" }
        return rows.single()
    }

    private fun identityDataJson(account: DemoAccount): String {
        val node = objectMapper.valueToTree<ObjectNode>(account.identityData)
        val nullFields = node.fields().asSequence().filter { it.value.isNull }.map { it.key }.toList()
        nullFields.forEach(node::remove)
        node.put("phone", account.phone)
        return objectMapper.writeValueAsString(node)
    }

    private fun identityReviewNote(catalog: DemoCatalog, roleCode: String): String = when (roleCode) {
        "INSTITUTION_LEGAL_REPRESENTATIVE" ->
            catalog.sharedContent.identity.reviewNotes.institutionLegalRepresentative
        "DOCTOR" -> catalog.sharedContent.identity.reviewNotes.doctor
        "CONSULTANT" -> catalog.sharedContent.identity.reviewNotes.consultant
        else -> error("Unsupported demo role")
    }

    private fun identityStorageKey(account: DemoAccount, document: DemoIdentityDocument): String =
        "${account.id}/${document.documentType}/${document.fileId}.png"

    private fun ensurePlaceholderFile(storageKey: String) {
        val path = resolvePrivatePath(storageKey)
        if (Files.exists(path)) {
            check(Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(PLACEHOLDER_PNG)) {
                "Existing demo identity placeholder has drifted"
            }
            return
        }
        Files.createDirectories(path.parent)
        Files.write(path, PLACEHOLDER_PNG)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) Files.deleteIfExists(path)
                }
            })
        }
    }

    private fun verifyPlaceholderFile(storageKey: String) {
        val path = resolvePrivatePath(storageKey)
        check(Files.isRegularFile(path)) { "Demo identity placeholder is missing" }
        check(Files.readAllBytes(path).contentEquals(PLACEHOLDER_PNG)) {
            "Demo identity placeholder content has drifted"
        }
    }

    private fun resolvePrivatePath(storageKey: String): Path {
        val root = Path.of(privateUploadDirectory).toAbsolutePath().normalize()
        val resolved = root.resolve(storageKey).normalize()
        check(resolved.startsWith(root)) { "Unsafe demo identity placeholder path" }
        check(!Files.isSymbolicLink(root)) { "Demo private upload root must not be a symbolic link" }
        var current = root
        root.relativize(resolved).forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current)) {
                check(!Files.isSymbolicLink(current)) {
                    "Demo identity placeholder path must not use symbolic links"
                }
            }
        }
        return resolved
    }

    private fun encodeList(values: List<String>): String = objectMapper.writeValueAsString(values)

    private fun assertTotalCount(table: String, expected: Int) {
        check(table in COUNTED_TABLES) { "Unapproved demo table name" }
        val actual = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)
        check(actual == expected) { "$table count drift: expected $expected, actual $actual" }
    }

    private fun countByIds(table: String, column: String, ids: List<String>): Int {
        check(table == "users" && column == "id") { "Unapproved demo lookup" }
        if (ids.isEmpty()) return 0
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM $table WHERE $column IN (${placeholders(ids.size)})",
            Int::class.java,
            *ids.toTypedArray(),
        )
    }

    private fun assertRows(
        label: String,
        sql: String,
        args: Array<Any>,
        expectedRows: List<List<Any?>>,
    ) {
        val actual = jdbcTemplate.query(
            sql,
            { rs, _ ->
                val columnCount = rs.metaData.columnCount
                (1..columnCount).map { index -> normalize(rs.getObject(index)) }
            },
            *args,
        )
        val expected = expectedRows.map { row -> row.map(::normalize) }
        check(actual == expected) {
            "$label content drift: expected ${expected.size} rows, actual ${actual.size} rows"
        }
    }

    private fun normalize(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> if (value) "1" else "0"
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> canonicalJsonOrOriginal(value)
        else -> value.toString()
    }

    private fun canonicalJsonOrOriginal(value: String): String {
        val trimmed = value.trim()
        if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return value
        return runCatching {
            canonicalJson(objectMapper.readTree(trimmed))
        }.getOrElse { value }
    }

    private fun canonicalJson(node: JsonNode): String = when {
        node.isObject -> node.fields().asSequence().toList().sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, child) ->
                "${objectMapper.writeValueAsString(key)}:${canonicalJson(child)}"
            }
        node.isArray -> node.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> objectMapper.writeValueAsString(node)
    }

    private fun managedRowCount(catalog: DemoCatalog): Int {
        val documents = catalog.accounts.sumOf { it.identityDocuments.size }
        return catalog.accounts.size * 3 + documents * 2 + catalog.institutions.size +
            catalog.legalRepresentativeMemberships.size + catalog.consultantMemberships.size +
            catalog.doctors.size * 2 + catalog.wallets.size + catalog.projects.size +
            catalog.institutionProjects.size + catalog.doctorProjects.size * 2 +
            catalog.projectRequests.size
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

    private companion object {
        val DISALLOWED_BUSINESS_TABLES = setOf(
            "agent_assessments", "agent_messages", "agent_plan_items", "agent_plans",
            "agent_safety_events", "agent_sessions", "agent_turns", "agent_user_profiles",
            "auth_sessions", "banners", "comments", "consultant_institution_change_requests",
            "coupons", "diaries", "diary_shares", "dm_conversations", "dm_messages",
            "doctor_institution_change_requests", "doctor_project_change_requests", "expert_articles",
            "favorites", "follows", "likes", "notifications", "order_status_logs", "orders",
            "payment_compensation_cases", "payment_events", "payments",
            "platform_cooperation_agreements", "reconciliation_issues", "refresh_tokens",
            "refund_evidence_files", "refund_items", "refunds", "reports", "reviews",
            "settlement_allocations", "settlements", "split_config_proposals", "user_coupons",
            "wallet_ledger_entries", "account_deletion_requests", "user_media_assets",
        )
        val COUNTED_TABLES = setOf(
            "users", "private_files", "identity_applications", "identity_application_documents",
            "user_roles", "institutions", "institution_memberships", "doctors",
            "doctor_institutions", "wallets", "projects", "institution_projects",
            "doctor_projects", "doctor_institution_project_configs",
            "professional_project_requests",
        ) + DISALLOWED_BUSINESS_TABLES
        val PLACEHOLDER_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val PLACEHOLDER_PNG_SHA256: String = MessageDigest.getInstance("SHA-256")
            .digest(PLACEHOLDER_PNG)
            .joinToString("") { "%02x".format(it) }
    }
}
