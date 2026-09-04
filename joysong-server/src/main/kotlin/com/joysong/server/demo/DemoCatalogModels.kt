package com.joysong.server.demo

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

data class LoadedDemoCatalog(
    val catalog: DemoCatalog,
    val sha256: String,
    val path: Path,
)

data class DemoCatalog(
    val schemaVersion: Int,
    val datasetVersion: String,
    val namespace: String,
    val sharedContent: DemoSharedContent,
    val accounts: List<DemoAccount>,
    val institutions: List<DemoInstitution>,
    val legalRepresentativeMemberships: List<DemoLegalRepresentativeMembership>,
    val doctors: List<DemoDoctor>,
    val consultantMemberships: List<DemoConsultantMembership>,
    val wallets: List<DemoWallet>,
    val projects: List<DemoProject>,
    val institutionProjects: List<DemoInstitutionProject>,
    val doctorProjects: List<DemoDoctorProject>,
    val projectRequests: List<DemoProjectRequest>,
)

data class DemoSharedContent(
    val account: DemoSharedAccountContent,
    val identity: DemoSharedIdentityContent,
    val institution: DemoSharedInstitutionContent,
    val doctor: DemoSharedDoctorContent,
    val membership: DemoSharedMembershipContent,
    val project: DemoSharedProjectContent,
    val institutionProject: DemoSharedInstitutionProjectContent,
    val projectRequest: DemoSharedProjectRequestContent,
    val doctorProjectWorkflow: DemoSharedDoctorProjectWorkflowContent,
)

data class DemoSharedAccountContent(
    val bio: String,
    val supervisorDisplayName: String,
)

data class DemoSharedIdentityContent(
    val documentOriginalName: String,
    val reviewNotes: DemoIdentityReviewNotes,
)

data class DemoIdentityReviewNotes(
    @JsonProperty("INSTITUTION_LEGAL_REPRESENTATIVE")
    val institutionLegalRepresentative: String,
    @JsonProperty("DOCTOR")
    val doctor: String,
    @JsonProperty("CONSULTANT")
    val consultant: String,
)

data class DemoSharedInstitutionContent(
    val coverImage: String,
    val images: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val isVerified: Boolean,
    val consultationCount: Int,
    val userCount: Int,
    val caseCount: Int,
    val credentials: String,
    val credentialImages: String,
    val specialties: String,
    val tags: String,
    val businessHours: String,
)

data class DemoSharedDoctorContent(
    val avatar: String,
    val contactPhone: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val isVerified: Boolean,
    val consultationCount: Int,
    val credentials: String,
    val credentialImages: String,
    val caseCount: Int,
    val certificationTags: String,
)

data class DemoSharedMembershipContent(
    val legalRequestNote: String,
    val legalReviewNote: String,
    val doctorPracticeRequestNote: String,
    val doctorPracticeReviewNote: String,
    val consultantRequestNote: String,
    val consultantReviewNote: String,
)

data class DemoSharedProjectContent(
    val coverImage: String,
    val images: String,
    val rating: BigDecimal,
)

data class DemoSharedInstitutionProjectContent(
    val coverImage: String,
    val images: String,
    val rating: BigDecimal,
)

data class DemoSharedProjectRequestContent(
    val coverImage: String,
    val images: List<String>,
    val salesCount: Int,
)

data class DemoSharedDoctorProjectWorkflowContent(
    val joinNote: String,
    val joinReviewNote: String,
    val suspensionNote: String,
    val suspensionReviewNote: String,
)

data class DemoAccount(
    val id: String,
    val code: String,
    val phone: String,
    val roleCode: String,
    val displayName: String,
    val accountState: String,
    val identityApplicationId: String,
    val identityStatus: String,
    val identityData: DemoIdentityData,
    val identityDocuments: List<DemoIdentityDocument>,
)

data class DemoIdentityData(
    val realName: String,
    val idNumber: String,
    val institutionName: String? = null,
    val businessLicenseNo: String? = null,
    val region: String? = null,
    val address: String? = null,
    val department: String? = null,
    val title: String? = null,
    val qualificationNo: String? = null,
    val practiceNo: String? = null,
    val experience: String? = null,
    val proofDescription: String? = null,
    val reason: String? = null,
)

data class DemoIdentityDocument(
    val code: String,
    val fileId: String,
    val documentType: String,
)

data class DemoInstitution(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val description: String,
    val address: String,
    val contactPhone: String,
)

data class DemoLegalRepresentativeMembership(
    val id: String,
    val code: String,
    val accountCode: String,
    val institutionCode: String,
    val isPrimary: Boolean,
)

data class DemoDoctor(
    val code: String,
    val accountCode: String,
    val institutionCode: String,
    val practiceRelationId: String,
    val practiceRelationCode: String,
    val name: String,
    val title: String,
    val specialty: String,
    val introduction: String,
)

data class DemoConsultantMembership(
    val id: String,
    val code: String,
    val accountCode: String,
    val institutionCode: String,
    val isPrimary: Boolean,
)

data class DemoWallet(
    val code: String,
    val ownerType: String,
    val ownerCode: String,
    val currency: String,
    val pendingMinor: Long,
    val availableMinor: Long,
    val frozenMinor: Long,
)

data class DemoProject(
    val id: String,
    val code: String,
    val name: String,
    val category: String,
    val description: String,
    val referencePrice: BigDecimal,
    val currency: String,
    val salesCount: Int,
    val reviewCount: Int,
    val caseCount: Int,
    val tags: List<String>,
    val categoryTags: List<String>,
    val slogan: String,
    val detailContent: String,
)

data class DemoInstitutionProject(
    val id: String,
    val code: String,
    val institutionCode: String,
    val projectCode: String,
    val name: String,
    val description: String,
    val price: BigDecimal,
    val isActive: Boolean,
    val originalPrice: BigDecimal,
    val currency: String,
    val tags: List<String>,
    val slogan: String,
    val detailContent: String,
    val salesCount: Int,
    val reviewCount: Int,
    val caseCount: Int,
    val consultationCount: Int,
)

data class DemoDoctorProject(
    val id: String,
    val code: String,
    val doctorCode: String,
    val institutionProjectCode: String,
    val description: String,
    val price: BigDecimal,
    val isActive: Boolean,
    val serviceTags: List<String>,
    val scheduleNote: String,
    val splitConfigId: String,
    val splitConfigCode: String,
    val consultationFee: BigDecimal,
    val commissionRate: BigDecimal,
    val institutionRate: BigDecimal,
    val medicalListPrice: BigDecimal,
)

data class DemoProjectRequest(
    val id: String,
    val code: String,
    val requestType: String,
    val doctorCode: String,
    val institutionCode: String? = null,
    val projectCode: String? = null,
    val proposedName: String,
    val category: String,
    val description: String,
    val reason: String,
    val currency: String,
    val referencePrice: BigDecimal? = null,
    val price: BigDecimal? = null,
    val originalPrice: BigDecimal? = null,
    val isActive: Boolean? = null,
    val consultationFee: BigDecimal? = null,
    val commissionRate: BigDecimal? = null,
    val institutionRate: BigDecimal? = null,
    val status: String,
    val reviewNote: String,
    val tags: List<String>,
    val slogan: String,
    val detailContent: String,
    val categoryTags: List<String>? = null,
)

@Component
class DemoCatalogLoader(objectMapper: ObjectMapper) {
    private val reader = objectMapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)
        .readerFor(DemoCatalog::class.java)

    fun load(path: Path): LoadedDemoCatalog {
        val resolvedPath = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(resolvedPath)) { "Demo catalog is not a regular file: $resolvedPath" }
        val rawBytes = Files.readAllBytes(resolvedPath)
        require(rawBytes.isNotEmpty()) { "Demo catalog is empty: $resolvedPath" }
        val catalog: DemoCatalog = reader.readValue(rawBytes)
        DemoCatalogValidator.validate(catalog)
        return LoadedDemoCatalog(
            catalog = catalog,
            sha256 = sha256(rawBytes),
            path = resolvedPath,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}

private object DemoCatalogValidator {
    private val codePattern = Regex("^[A-Z][A-Z0-9-]{2,99}$")
    private val phonePattern = Regex("^\\+[1-9][0-9]{7,14}$")
    private val datasetVersionPattern = Regex("^[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}\\.[0-9]+$")
    private val namespacePattern = Regex("^[a-z0-9][a-z0-9-]{2,63}$")
    private val identityNumberPattern = Regex("^[0-9A-Za-z]{6,30}$")
    private val allowedRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE", "DOCTOR", "CONSULTANT")
    private val requiredIdentityDocuments = mapOf(
        "INSTITUTION_LEGAL_REPRESENTATIVE" to setOf("BUSINESS_LICENSE", "ID_CARD_FRONT", "ID_CARD_BACK"),
        "DOCTOR" to setOf(
            "ID_CARD_FRONT",
            "ID_CARD_BACK",
            "ID_CARD_HANDHELD",
            "DOCTOR_QUALIFICATION",
            "DOCTOR_PRACTICE_CERTIFICATE",
        ),
        "CONSULTANT" to setOf("ID_CARD_FRONT", "ID_CARD_BACK", "CONSULTANT_PROOF"),
    )

    fun validate(catalog: DemoCatalog) {
        require(catalog.schemaVersion == 1) { "Unsupported demo catalog schemaVersion: ${catalog.schemaVersion}" }
        requiredText(catalog.datasetVersion, "datasetVersion", 64)
        require(datasetVersionPattern.matches(catalog.datasetVersion)) { "datasetVersion has an invalid format" }
        requiredText(catalog.namespace, "namespace", 64)
        require(namespacePattern.matches(catalog.namespace)) { "namespace has an invalid format" }

        require(catalog.accounts.size == 20) { "accounts must contain exactly 20 entries" }
        require(catalog.institutions.size == 6) { "institutions must contain exactly 6 entries" }
        require(catalog.legalRepresentativeMemberships.size == 6) {
            "legalRepresentativeMemberships must contain exactly 6 entries"
        }
        require(catalog.doctors.size == 10) { "doctors must contain exactly 10 entries" }
        require(catalog.consultantMemberships.size == 6) {
            "consultantMemberships must contain exactly 6 entries"
        }
        require(catalog.wallets.size == 22) { "wallets must contain exactly 22 entries" }
        require(catalog.projects.size == 12) { "projects must contain exactly 12 entries" }
        require(catalog.institutionProjects.size == 21) { "institutionProjects must contain exactly 21 entries" }
        require(catalog.doctorProjects.size == 30) { "doctorProjects must contain exactly 30 entries" }
        require(catalog.projectRequests.size == 5) { "projectRequests must contain exactly 5 entries" }

        validateSharedContent(catalog.sharedContent)
        validateDefinitionKeys(catalog)
        validateAccounts(catalog.accounts)
        validateRelationships(catalog)
        validateProjects(catalog)
        validateProjectRequests(catalog)
    }

    private fun validateSharedContent(shared: DemoSharedContent) {
        requiredText(shared.account.bio, "sharedContent.account.bio", 500)
        requiredText(shared.account.supervisorDisplayName, "sharedContent.account.supervisorDisplayName", 100)
        requiredText(shared.identity.documentOriginalName, "sharedContent.identity.documentOriginalName", 255)
        requiredText(
            shared.identity.reviewNotes.institutionLegalRepresentative,
            "sharedContent.identity.reviewNotes.INSTITUTION_LEGAL_REPRESENTATIVE",
            1_000,
        )
        requiredText(shared.identity.reviewNotes.doctor, "sharedContent.identity.reviewNotes.DOCTOR", 1_000)
        requiredText(shared.identity.reviewNotes.consultant, "sharedContent.identity.reviewNotes.CONSULTANT", 1_000)

        rating(shared.institution.rating, "sharedContent.institution.rating")
        count(shared.institution.reviewCount, "sharedContent.institution.reviewCount")
        count(shared.institution.consultationCount, "sharedContent.institution.consultationCount")
        count(shared.institution.userCount, "sharedContent.institution.userCount")
        count(shared.institution.caseCount, "sharedContent.institution.caseCount")
        require(shared.institution.isVerified) { "sharedContent.institution.isVerified must be true" }
        requiredText(shared.institution.credentials, "sharedContent.institution.credentials", 2_000)
        requiredText(shared.institution.specialties, "sharedContent.institution.specialties", 500)
        requiredText(shared.institution.tags, "sharedContent.institution.tags", 500)
        requiredText(shared.institution.businessHours, "sharedContent.institution.businessHours", 200)

        rating(shared.doctor.rating, "sharedContent.doctor.rating")
        count(shared.doctor.reviewCount, "sharedContent.doctor.reviewCount")
        count(shared.doctor.consultationCount, "sharedContent.doctor.consultationCount")
        count(shared.doctor.caseCount, "sharedContent.doctor.caseCount")
        require(shared.doctor.isVerified) { "sharedContent.doctor.isVerified must be true" }
        requiredText(shared.doctor.credentials, "sharedContent.doctor.credentials", 2_000)
        requiredText(shared.doctor.certificationTags, "sharedContent.doctor.certificationTags", 500)

        with(shared.membership) {
            requiredText(legalRequestNote, "sharedContent.membership.legalRequestNote", 1_000)
            requiredText(legalReviewNote, "sharedContent.membership.legalReviewNote", 1_000)
            requiredText(doctorPracticeRequestNote, "sharedContent.membership.doctorPracticeRequestNote", 1_000)
            requiredText(doctorPracticeReviewNote, "sharedContent.membership.doctorPracticeReviewNote", 1_000)
            requiredText(consultantRequestNote, "sharedContent.membership.consultantRequestNote", 1_000)
            requiredText(consultantReviewNote, "sharedContent.membership.consultantReviewNote", 1_000)
        }
        rating(shared.project.rating, "sharedContent.project.rating")
        rating(shared.institutionProject.rating, "sharedContent.institutionProject.rating")
        stringList(shared.projectRequest.images, "sharedContent.projectRequest.images", allowEmpty = true)
        count(shared.projectRequest.salesCount, "sharedContent.projectRequest.salesCount")
        with(shared.doctorProjectWorkflow) {
            requiredText(joinNote, "sharedContent.doctorProjectWorkflow.joinNote", 1_000)
            requiredText(joinReviewNote, "sharedContent.doctorProjectWorkflow.joinReviewNote", 1_000)
            requiredText(suspensionNote, "sharedContent.doctorProjectWorkflow.suspensionNote", 1_000)
            requiredText(suspensionReviewNote, "sharedContent.doctorProjectWorkflow.suspensionReviewNote", 1_000)
        }
    }

    private fun validateDefinitionKeys(catalog: DemoCatalog) {
        val codes = mutableMapOf<String, String>()
        val ids = mutableMapOf<String, String>()

        fun code(value: String, owner: String) {
            require(codePattern.matches(value)) { "$owner has an invalid code: $value" }
            val previous = codes.putIfAbsent(value, owner)
            require(previous == null) { "Duplicate code $value in $owner and $previous" }
        }

        fun id(value: String, owner: String) {
            requireCanonicalUuid(value, owner)
            val previous = ids.putIfAbsent(value, owner)
            require(previous == null) { "Duplicate id $value in $owner and $previous" }
        }

        catalog.accounts.forEach { account ->
            code(account.code, "account ${account.code}")
            id(account.id, "account ${account.code}")
            id(account.identityApplicationId, "identity application ${account.code}")
            account.identityDocuments.forEach { document ->
                code(document.code, "identity document ${document.code}")
                id(document.fileId, "identity document ${document.code}")
            }
        }
        catalog.institutions.forEach { institution ->
            code(institution.code, "institution ${institution.code}")
            id(institution.id, "institution ${institution.code}")
        }
        catalog.legalRepresentativeMemberships.forEach { membership ->
            code(membership.code, "legal representative membership ${membership.code}")
            id(membership.id, "legal representative membership ${membership.code}")
        }
        catalog.doctors.forEach { doctor ->
            code(doctor.code, "doctor ${doctor.code}")
            code(doctor.practiceRelationCode, "doctor practice relation ${doctor.practiceRelationCode}")
            id(doctor.practiceRelationId, "doctor practice relation ${doctor.practiceRelationCode}")
        }
        catalog.consultantMemberships.forEach { membership ->
            code(membership.code, "consultant membership ${membership.code}")
            id(membership.id, "consultant membership ${membership.code}")
        }
        catalog.wallets.forEach { wallet -> code(wallet.code, "wallet ${wallet.code}") }
        catalog.projects.forEach { project ->
            code(project.code, "project ${project.code}")
            id(project.id, "project ${project.code}")
        }
        catalog.institutionProjects.forEach { project ->
            code(project.code, "institution project ${project.code}")
            id(project.id, "institution project ${project.code}")
        }
        catalog.doctorProjects.forEach { project ->
            code(project.code, "doctor project ${project.code}")
            id(project.id, "doctor project ${project.code}")
            code(project.splitConfigCode, "split config ${project.splitConfigCode}")
            id(project.splitConfigId, "split config ${project.splitConfigCode}")
        }
        catalog.projectRequests.forEach { request ->
            code(request.code, "project request ${request.code}")
            id(request.id, "project request ${request.code}")
        }
    }

    private fun validateAccounts(accounts: List<DemoAccount>) {
        require(accounts.map { it.phone }.toSet().size == accounts.size) { "Account phones must be unique" }
        require(accounts.groupingBy { it.roleCode }.eachCount() == mapOf(
            "INSTITUTION_LEGAL_REPRESENTATIVE" to 4,
            "DOCTOR" to 10,
            "CONSULTANT" to 6,
        )) { "Account role distribution is invalid" }

        accounts.forEach { account ->
            require(phonePattern.matches(account.phone)) { "${account.code}.phone is not a valid demo E.164 number" }
            require(account.roleCode in allowedRoles) { "${account.code}.roleCode is unsupported" }
            require(account.accountState == "ACTIVE") { "${account.code}.accountState must be ACTIVE" }
            require(account.identityStatus == "APPROVED") { "${account.code}.identityStatus must be APPROVED" }
            requiredText(account.displayName, "${account.code}.displayName", 100)
            requiredText(account.identityData.realName, "${account.code}.identityData.realName", 50)
            require(identityNumberPattern.matches(account.identityData.idNumber)) {
                "${account.code}.identityData.idNumber has an invalid format"
            }
            require(account.identityData.realName == account.displayName) {
                "${account.code}.identityData.realName must match displayName"
            }

            when (account.roleCode) {
                "INSTITUTION_LEGAL_REPRESENTATIVE" -> with(account.identityData) {
                    requiredOptional(institutionName, "${account.code}.identityData.institutionName", 200)
                    requiredOptional(businessLicenseNo, "${account.code}.identityData.businessLicenseNo", 32)
                    requiredOptional(region, "${account.code}.identityData.region", 100)
                    requiredOptional(address, "${account.code}.identityData.address", 300)
                    requireAllNull(
                        account.code,
                        department,
                        title,
                        qualificationNo,
                        practiceNo,
                        experience,
                        proofDescription,
                        reason,
                    )
                }
                "DOCTOR" -> with(account.identityData) {
                    requiredOptional(department, "${account.code}.identityData.department", 100)
                    requiredOptional(title, "${account.code}.identityData.title", 100)
                    requiredOptional(qualificationNo, "${account.code}.identityData.qualificationNo", 100)
                    requiredOptional(practiceNo, "${account.code}.identityData.practiceNo", 100)
                    requiredOptional(reason, "${account.code}.identityData.reason", 500)
                    requireAllNull(
                        account.code,
                        institutionName,
                        businessLicenseNo,
                        region,
                        address,
                        experience,
                        proofDescription,
                    )
                }
                "CONSULTANT" -> with(account.identityData) {
                    requiredOptional(experience, "${account.code}.identityData.experience", 1_000)
                    requiredOptional(proofDescription, "${account.code}.identityData.proofDescription", 500)
                    requiredOptional(reason, "${account.code}.identityData.reason", 500)
                    requireAllNull(
                        account.code,
                        institutionName,
                        businessLicenseNo,
                        region,
                        address,
                        department,
                        title,
                        qualificationNo,
                        practiceNo,
                    )
                }
            }

            val expectedDocumentTypes = requiredIdentityDocuments.getValue(account.roleCode)
            val actualDocumentTypes = account.identityDocuments.map { document -> document.documentType }.toSet()
            require(actualDocumentTypes == expectedDocumentTypes) {
                "${account.code}.identityDocuments must exactly match the required document types"
            }
            require(account.identityDocuments.size == actualDocumentTypes.size) {
                "${account.code}.identityDocuments contains duplicate document types"
            }
        }
        require(accounts.sumOf { account -> account.identityDocuments.size } == 80) {
            "identityDocuments must contain exactly 80 entries"
        }
    }

    private fun validateRelationships(catalog: DemoCatalog) {
        val accounts = catalog.accounts.associateBy { it.code }
        val institutions = catalog.institutions.associateBy { it.code }

        catalog.institutions.forEach { institution ->
            requiredText(institution.name, "${institution.code}.name", 200)
            requiredText(institution.city, "${institution.code}.city", 100)
            requiredText(institution.description, "${institution.code}.description", 10_000)
            requiredText(institution.address, "${institution.code}.address", 500)
            require(institution.contactPhone.length <= 50) { "${institution.code}.contactPhone is too long" }
        }

        val legalByAccount = catalog.legalRepresentativeMemberships.groupBy { it.accountCode }
        catalog.legalRepresentativeMemberships.forEach { membership ->
            val account = accounts[membership.accountCode]
                ?: error("${membership.code} references unknown account ${membership.accountCode}")
            require(account.roleCode == "INSTITUTION_LEGAL_REPRESENTATIVE") {
                "${membership.code} must reference a legal representative account"
            }
            require(institutions.containsKey(membership.institutionCode)) {
                "${membership.code} references unknown institution ${membership.institutionCode}"
            }
        }
        require(legalByAccount.keys == accounts.values.filter {
            it.roleCode == "INSTITUTION_LEGAL_REPRESENTATIVE"
        }.map { it.code }.toSet()) { "Every legal representative account must have a membership" }
        legalByAccount.forEach { (accountCode, memberships) ->
            require(memberships.count { it.isPrimary } == 1) {
                "$accountCode must have exactly one primary legal representative membership"
            }
            val account = accounts.getValue(accountCode)
            val institution = institutions.getValue(memberships.single { it.isPrimary }.institutionCode)
            require(account.identityData.institutionName == institution.name) {
                "$accountCode primary institution name does not match its identity application"
            }
            require(account.identityData.region == institution.city) {
                "$accountCode primary institution city does not match its identity application"
            }
            require(account.identityData.address == institution.address) {
                "$accountCode primary institution address does not match its identity application"
            }
        }
        require(catalog.legalRepresentativeMemberships.groupingBy { it.institutionCode }.eachCount().values.all { it == 1 }) {
            "Every institution must have exactly one legal representative membership"
        }

        require(catalog.doctors.map { it.accountCode }.toSet().size == catalog.doctors.size) {
            "Doctor account references must be unique"
        }
        catalog.doctors.forEach { doctor ->
            val account = accounts[doctor.accountCode]
                ?: error("${doctor.code} references unknown account ${doctor.accountCode}")
            require(account.roleCode == "DOCTOR") { "${doctor.code} must reference a doctor account" }
            require(institutions.containsKey(doctor.institutionCode)) {
                "${doctor.code} references unknown institution ${doctor.institutionCode}"
            }
            requiredText(doctor.name, "${doctor.code}.name", 100)
            requiredText(doctor.title, "${doctor.code}.title", 100)
            requiredText(doctor.specialty, "${doctor.code}.specialty", 500)
            requiredText(doctor.introduction, "${doctor.code}.introduction", 10_000)
            require(doctor.name == account.displayName) { "${doctor.code}.name must match its account displayName" }
            require(doctor.title == account.identityData.title) { "${doctor.code}.title must match its identity application" }
        }
        require(catalog.doctors.map { it.accountCode }.toSet() == accounts.values.filter {
            it.roleCode == "DOCTOR"
        }.map { it.code }.toSet()) { "Every doctor account must have exactly one doctor profile" }

        require(catalog.consultantMemberships.all { it.isPrimary }) {
            "Every consultant membership must be primary in schemaVersion 1"
        }
        require(catalog.consultantMemberships.map { it.accountCode }.toSet().size == catalog.consultantMemberships.size) {
            "Consultant account references must be unique"
        }
        catalog.consultantMemberships.forEach { membership ->
            val account = accounts[membership.accountCode]
                ?: error("${membership.code} references unknown account ${membership.accountCode}")
            require(account.roleCode == "CONSULTANT") {
                "${membership.code} must reference a consultant account"
            }
            require(institutions.containsKey(membership.institutionCode)) {
                "${membership.code} references unknown institution ${membership.institutionCode}"
            }
        }
        require(catalog.consultantMemberships.map { it.accountCode }.toSet() == accounts.values.filter {
            it.roleCode == "CONSULTANT"
        }.map { it.code }.toSet()) { "Every consultant account must have exactly one membership" }
        require(catalog.consultantMemberships.groupingBy { it.institutionCode }.eachCount().values.all { it == 1 }) {
            "Every institution must have exactly one consultant membership"
        }

        val doctorCodes = catalog.doctors.map { it.code }.toSet()
        val consultantAccountCodes = catalog.consultantMemberships.map { it.accountCode }.toSet()
        val institutionCodes = institutions.keys
        val expectedWallets = buildSet {
            doctorCodes.forEach { add("DOCTOR:$it:USD") }
            consultantAccountCodes.forEach { add("CONSULTANT:$it:USD") }
            institutionCodes.forEach { add("INSTITUTION:$it:USD") }
        }
        val actualWallets = catalog.wallets.map { wallet ->
            require(wallet.ownerType in setOf("DOCTOR", "CONSULTANT", "INSTITUTION")) {
                "${wallet.code}.ownerType is unsupported"
            }
            require(wallet.currency == "USD") { "${wallet.code}.currency must be USD" }
            count(wallet.pendingMinor, "${wallet.code}.pendingMinor")
            count(wallet.availableMinor, "${wallet.code}.availableMinor")
            count(wallet.frozenMinor, "${wallet.code}.frozenMinor")
            when (wallet.ownerType) {
                "DOCTOR" -> require(wallet.ownerCode in doctorCodes) {
                    "${wallet.code} references unknown doctor ${wallet.ownerCode}"
                }
                "CONSULTANT" -> require(wallet.ownerCode in consultantAccountCodes) {
                    "${wallet.code} references unknown consultant ${wallet.ownerCode}"
                }
                "INSTITUTION" -> require(wallet.ownerCode in institutionCodes) {
                    "${wallet.code} references unknown institution ${wallet.ownerCode}"
                }
            }
            "${wallet.ownerType}:${wallet.ownerCode}:${wallet.currency}"
        }
        require(actualWallets.toSet().size == actualWallets.size) { "Wallet owner/currency keys must be unique" }
        require(actualWallets.toSet() == expectedWallets) { "Wallet set does not match the professional catalog" }
    }

    private fun validateProjects(catalog: DemoCatalog) {
        val institutions = catalog.institutions.associateBy { it.code }
        val projects = catalog.projects.associateBy { it.code }
        val doctors = catalog.doctors.associateBy { it.code }

        catalog.projects.forEach { project ->
            requiredText(project.name, "${project.code}.name", 200)
            requiredText(project.category, "${project.code}.category", 100)
            requiredText(project.description, "${project.code}.description", 10_000)
            money(project.referencePrice, "${project.code}.referencePrice")
            require(project.currency == "USD") { "${project.code}.currency must be USD" }
            count(project.salesCount, "${project.code}.salesCount")
            count(project.reviewCount, "${project.code}.reviewCount")
            count(project.caseCount, "${project.code}.caseCount")
            require(project.salesCount == 0 && project.reviewCount == 0 && project.caseCount == 0) {
                "${project.code} catalog statistics must be zero"
            }
            stringList(project.tags, "${project.code}.tags")
            stringList(project.categoryTags, "${project.code}.categoryTags")
            requiredText(project.slogan, "${project.code}.slogan", 500)
            requiredText(project.detailContent, "${project.code}.detailContent", 50_000)
        }

        val institutionProjectKeys = mutableSetOf<String>()
        catalog.institutionProjects.forEach { project ->
            require(institutions.containsKey(project.institutionCode)) {
                "${project.code} references unknown institution ${project.institutionCode}"
            }
            require(projects.containsKey(project.projectCode)) {
                "${project.code} references unknown project ${project.projectCode}"
            }
            require(institutionProjectKeys.add("${project.institutionCode}:${project.projectCode}")) {
                "Duplicate institution/project binding for ${project.institutionCode}:${project.projectCode}"
            }
            requiredText(project.name, "${project.code}.name", 200)
            requiredText(project.description, "${project.code}.description", 10_000)
            money(project.price, "${project.code}.price")
            money(project.originalPrice, "${project.code}.originalPrice")
            require(project.originalPrice >= project.price) { "${project.code}.originalPrice must not be below price" }
            require(project.currency == "USD") { "${project.code}.currency must be USD" }
            stringList(project.tags, "${project.code}.tags")
            requiredText(project.slogan, "${project.code}.slogan", 500)
            requiredText(project.detailContent, "${project.code}.detailContent", 50_000)
            count(project.salesCount, "${project.code}.salesCount")
            count(project.reviewCount, "${project.code}.reviewCount")
            count(project.caseCount, "${project.code}.caseCount")
            count(project.consultationCount, "${project.code}.consultationCount")
            require(
                project.salesCount == 0 && project.reviewCount == 0 &&
                    project.caseCount == 0 && project.consultationCount == 0
            ) { "${project.code} catalog statistics must be zero" }
        }
        require(catalog.institutionProjects.count { it.isActive } == 18) {
            "institutionProjects must contain exactly 18 active entries"
        }
        require(catalog.institutionProjects.count { !it.isActive } == 3) {
            "institutionProjects must contain exactly 3 inactive entries"
        }

        val institutionProjects = catalog.institutionProjects.associateBy { it.code }
        val doctorProjectKeys = mutableSetOf<String>()
        catalog.doctorProjects.forEach { project ->
            val doctor = doctors[project.doctorCode]
                ?: error("${project.code} references unknown doctor ${project.doctorCode}")
            val institutionProject = institutionProjects[project.institutionProjectCode]
                ?: error("${project.code} references unknown institution project ${project.institutionProjectCode}")
            require(doctor.institutionCode == institutionProject.institutionCode) {
                "${project.code} doctor does not practice at the institution project institution"
            }
            require(doctorProjectKeys.add("${project.doctorCode}:${project.institutionProjectCode}")) {
                "Duplicate doctor/institution-project binding for ${project.code}"
            }
            requiredText(project.description, "${project.code}.description", 10_000)
            money(project.price, "${project.code}.price")
            stringList(project.serviceTags, "${project.code}.serviceTags")
            requiredText(project.scheduleNote, "${project.code}.scheduleNote", 500)
            money(project.consultationFee, "${project.code}.consultationFee")
            rate(project.commissionRate, "${project.code}.commissionRate")
            rate(project.institutionRate, "${project.code}.institutionRate")
            require(project.commissionRate + project.institutionRate <= HUNDRED) {
                "${project.code} commissionRate + institutionRate must not exceed 100"
            }
            money(project.medicalListPrice, "${project.code}.medicalListPrice")
            require(project.isActive == institutionProject.isActive) {
                "${project.code}.isActive must match its institution project"
            }
        }
        require(catalog.doctorProjects.count { it.isActive } == 27) {
            "doctorProjects must contain exactly 27 active entries"
        }
        require(catalog.doctorProjects.count { !it.isActive } == 3) {
            "doctorProjects must contain exactly 3 inactive entries"
        }
        val boundInstitutionProjects = catalog.doctorProjects.map { it.institutionProjectCode }.toSet()
        require(boundInstitutionProjects == institutionProjects.keys) {
            "Every institution project must have at least one doctor project"
        }
    }

    private fun validateProjectRequests(catalog: DemoCatalog) {
        val doctors = catalog.doctors.associateBy { it.code }
        val institutions = catalog.institutions.associateBy { it.code }
        val projects = catalog.projects.associateBy { it.code }
        val existingInstitutionProjects = catalog.institutionProjects
            .map { "${it.institutionCode}:${it.projectCode}" }
            .toSet()

        require(catalog.projectRequests.count { it.requestType == "INSTITUTION" } == 3) {
            "projectRequests must contain exactly 3 institution requests"
        }
        require(catalog.projectRequests.count { it.requestType == "PLATFORM" } == 2) {
            "projectRequests must contain exactly 2 platform requests"
        }
        require(catalog.projectRequests.count { it.status == "PENDING" } == 4) {
            "projectRequests must contain exactly 4 pending requests"
        }
        require(catalog.projectRequests.count { it.status == "REJECTED" } == 1) {
            "projectRequests must contain exactly 1 rejected request"
        }

        catalog.projectRequests.forEach { request ->
            val doctor = doctors[request.doctorCode]
                ?: error("${request.code} references unknown doctor ${request.doctorCode}")
            require(request.requestType in setOf("PLATFORM", "INSTITUTION")) {
                "${request.code}.requestType is unsupported"
            }
            require(request.status in setOf("PENDING", "REJECTED")) {
                "${request.code}.status is unsupported"
            }
            require(request.currency == "USD") { "${request.code}.currency must be USD" }
            requiredText(request.proposedName, "${request.code}.proposedName", 200)
            requiredText(request.category, "${request.code}.category", 100)
            requiredText(request.description, "${request.code}.description", 10_000)
            requiredText(request.reason, "${request.code}.reason", 10_000)
            stringList(request.tags, "${request.code}.tags")
            requiredText(request.slogan, "${request.code}.slogan", 500)
            requiredText(request.detailContent, "${request.code}.detailContent", 50_000)

            if (request.status == "REJECTED") {
                requiredText(request.reviewNote, "${request.code}.reviewNote", 1_000)
            } else {
                require(request.reviewNote.isBlank()) { "${request.code}.reviewNote must be blank while pending" }
            }

            when (request.requestType) {
                "PLATFORM" -> {
                    require(request.institutionCode == null) { "${request.code}.institutionCode must be null" }
                    require(request.projectCode == null) { "${request.code}.projectCode must be null" }
                    money(requireNotNull(request.referencePrice) {
                        "${request.code}.referencePrice is required"
                    }, "${request.code}.referencePrice")
                    require(request.price == null) { "${request.code}.price must be null" }
                    require(request.originalPrice == null) { "${request.code}.originalPrice must be null" }
                    require(request.isActive == null) { "${request.code}.isActive must be null" }
                    require(request.consultationFee == null) { "${request.code}.consultationFee must be null" }
                    require(request.commissionRate == null) { "${request.code}.commissionRate must be null" }
                    require(request.institutionRate == null) { "${request.code}.institutionRate must be null" }
                    stringList(requireNotNull(request.categoryTags) {
                        "${request.code}.categoryTags is required"
                    }, "${request.code}.categoryTags")
                }
                "INSTITUTION" -> {
                    val institutionCode = requireNotNull(request.institutionCode) {
                        "${request.code}.institutionCode is required"
                    }
                    val projectCode = requireNotNull(request.projectCode) {
                        "${request.code}.projectCode is required"
                    }
                    require(institutions.containsKey(institutionCode)) {
                        "${request.code} references unknown institution $institutionCode"
                    }
                    require(projects.containsKey(projectCode)) {
                        "${request.code} references unknown project $projectCode"
                    }
                    require(doctor.institutionCode == institutionCode) {
                        "${request.code} doctor does not practice at $institutionCode"
                    }
                    require("$institutionCode:$projectCode" !in existingInstitutionProjects) {
                        "${request.code} duplicates an existing institution project"
                    }
                    require(request.referencePrice == null) { "${request.code}.referencePrice must be null" }
                    val price = requireNotNull(request.price) { "${request.code}.price is required" }
                    money(price, "${request.code}.price")
                    request.originalPrice?.let { originalPrice ->
                        money(originalPrice, "${request.code}.originalPrice")
                        require(originalPrice >= price) { "${request.code}.originalPrice must not be below price" }
                    }
                    requireNotNull(request.isActive) { "${request.code}.isActive is required" }
                    money(requireNotNull(request.consultationFee) {
                        "${request.code}.consultationFee is required"
                    }, "${request.code}.consultationFee")
                    val commissionRate = requireNotNull(request.commissionRate) {
                        "${request.code}.commissionRate is required"
                    }
                    val institutionRate = requireNotNull(request.institutionRate) {
                        "${request.code}.institutionRate is required"
                    }
                    rate(commissionRate, "${request.code}.commissionRate")
                    rate(institutionRate, "${request.code}.institutionRate")
                    require(commissionRate + institutionRate <= HUNDRED) {
                        "${request.code} commissionRate + institutionRate must not exceed 100"
                    }
                    require(request.categoryTags == null) { "${request.code}.categoryTags must be null" }
                }
            }
        }
    }

    private fun requireCanonicalUuid(value: String, label: String) {
        val parsed = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("$label has an invalid UUID: $value")
        }
        require(parsed.toString() == value) { "$label UUID must use canonical lowercase form: $value" }
    }

    private fun requiredText(value: String, label: String, maxLength: Int): String = value.trim().also {
        require(it.isNotEmpty()) { "$label must not be blank" }
        require(it.length <= maxLength) { "$label exceeds $maxLength characters" }
    }

    private fun requiredOptional(value: String?, label: String, maxLength: Int): String =
        requiredText(requireNotNull(value) { "$label is required" }, label, maxLength)

    private fun requireAllNull(label: String, vararg values: String?) {
        require(values.all { it == null }) { "$label identityData contains fields for another role" }
    }

    private fun stringList(values: List<String>, label: String, allowEmpty: Boolean = false) {
        require(allowEmpty || values.isNotEmpty()) { "$label must not be empty" }
        require(values.size <= 20) { "$label contains too many values" }
        val normalized = values.mapIndexed { index, value -> requiredText(value, "$label[$index]", 500) }
        require(normalized.toSet().size == normalized.size) { "$label contains duplicate values" }
    }

    private fun rating(value: BigDecimal, label: String) {
        require(value >= BigDecimal.ZERO && value <= FIVE) { "$label must be between 0 and 5" }
        require(value.scale() <= 1) { "$label must have at most one decimal place" }
    }

    private fun money(value: BigDecimal, label: String) {
        require(value >= BigDecimal.ZERO) { "$label must not be negative" }
        require(value.scale() <= 2) { "$label must have at most two decimal places" }
    }

    private fun rate(value: BigDecimal, label: String) {
        require(value >= BigDecimal.ZERO && value <= HUNDRED) { "$label must be between 0 and 100" }
        require(value.scale() <= 2) { "$label must have at most two decimal places" }
    }

    private fun count(value: Int, label: String) {
        require(value >= 0) { "$label must not be negative" }
    }

    private fun count(value: Long, label: String) {
        require(value >= 0) { "$label must not be negative" }
    }

    private val FIVE = BigDecimal("5")
    private val HUNDRED = BigDecimal("100")
}
