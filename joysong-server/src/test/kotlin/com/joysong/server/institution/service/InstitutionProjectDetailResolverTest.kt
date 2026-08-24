package com.joysong.server.institution.service

import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.project.entity.ProjectEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InstitutionProjectDetailResolverTest {
    private val resolver = InstitutionProjectDetailResolver()
    private val project = ProjectEntity(
        id = "project-1",
        name = "公共名称",
        category = "公共分类",
        description = "公共简介",
        rating = BigDecimal("4.6"),
        reviewCount = 120,
        tags = "自然,恢复快",
        slogan = "公共宣传语",
        detailContent = "<p>公共详情</p>",
        coverImage = "public-cover",
        images = "public-1,public-2",
        salesCount = 999
    )

    @Test
    fun `null and blank overrides inherit project details`() {
        val resolved = resolver.resolve(
            InstitutionProjectEntity(
                id = "ip-1",
                institutionId = "institution-1",
                projectId = project.id,
                name = "   ",
                price = BigDecimal("1000"),
                salesCount = 12
            ),
            project
        )

        assertEquals(project.name, resolved.name)
        assertEquals(project.category, resolved.category)
        assertEquals(project.rating, resolved.rating)
        assertEquals(project.detailContent, resolved.detailContent)
        assertEquals(project.coverImage, resolved.coverImage)
        assertEquals(12, resolved.salesCount)
    }

    @Test
    fun `each configured field overrides its project field and zero remains explicit`() {
        val resolved = resolver.resolve(
            InstitutionProjectEntity(
                id = "ip-2",
                institutionId = "institution-1",
                projectId = project.id,
                name = "机构名称",
                category = "机构分类",
                description = "机构简介",
                rating = BigDecimal.ZERO,
                reviewCount = 0,
                tags = "机构标签",
                slogan = "机构宣传语",
                detailContent = "<p>机构详情</p>",
                coverImage = "custom-cover",
                images = "custom-image",
                price = BigDecimal("1000")
            ),
            project
        )

        assertEquals("机构名称", resolved.name)
        assertEquals(BigDecimal.ZERO, resolved.rating)
        assertEquals(0, resolved.reviewCount)
        assertEquals("<p>机构详情</p>", resolved.detailContent)
        assertEquals("custom-cover", resolved.coverImage)
        assertEquals("custom-image", resolved.images)
    }

    @Test
    fun `normalization converts blank input to inheritance marker`() {
        assertNull(resolver.normalize("  "))
        assertEquals("已配置", resolver.normalize("  已配置  "))
        assertNull(resolver.normalizeRichText("<p><br></p>"))
    }

    @Test
    fun `null inherited media resolves to the platform project media`() {
        val project = ProjectEntity(id = "project-1", name = "平台项目", coverImage = "platform-cover", images = "platform-images")

        val resolved = resolver.resolve(
            InstitutionProjectEntity(
                id = "institution-project-1",
                institutionId = "institution-1",
                projectId = project.id,
                price = BigDecimal("100.00"),
                coverImage = null,
                images = null
            ),
            project
        )

        assertEquals("platform-cover", resolved.coverImage)
        assertEquals("platform-images", resolved.images)
    }
}
