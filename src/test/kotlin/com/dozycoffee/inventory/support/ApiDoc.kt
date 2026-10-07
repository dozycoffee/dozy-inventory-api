package com.dozycoffee.inventory.support

import com.epages.restdocs.apispec.ResourceSnippetDetails
import com.epages.restdocs.apispec.Schema
import com.epages.restdocs.apispec.WebTestClientRestDocumentationWrapper.document
import com.epages.restdocs.apispec.WebTestClientRestDocumentationWrapper.resourceDetails
import org.springframework.http.HttpHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/**
 * 컨트롤러 테스트에서 REST Docs 스니펫을 만드는 도우미. 스니펫이 OpenAPI 문서(`build/api-spec/openapi3.yaml`)가 된다.
 * 문서화한 요청·응답 필드와 실제 값이 다르면 테스트가 실패한다.
 */
object ApiDoc {
    /** 인증이 필요한 요청의 토큰 헤더 스니펫. 실제 토큰은 문서에 남지 않도록 [operation]이 치환한다 */
    val authorization: Snippet =
        requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION).description("Bearer 액세스 토큰. 호출에 필요한 inventory role은 API 설명에 적는다"))

    /** 오류 응답(Problem Details)의 필드 스니펫. `errors`는 검증 실패(400)에만 있다 */
    fun problem(withErrors: Boolean = false): Snippet =
        responseFields(
            listOfNotNull(
                fieldWithPath("type").type(JsonFieldType.STRING).description("오류 유형 URI"),
                fieldWithPath("title").type(JsonFieldType.STRING).description("오류 제목"),
                fieldWithPath("status").type(JsonFieldType.NUMBER).description("HTTP 상태 코드"),
                fieldWithPath("detail").type(JsonFieldType.STRING).description("오류 설명"),
                fieldWithPath("instance").type(JsonFieldType.STRING).description("요청 경로"),
                fieldWithPath("code").type(JsonFieldType.STRING).description("오류 코드(예: VALIDATION_FAILED, INV_PRODUCT_NOT_FOUND)"),
                fieldWithPath("traceId").type(JsonFieldType.STRING).description("추적 ID. 응답 헤더 X-Trace-Id와 같다"),
                if (withErrors) {
                    subsectionWithPath("errors").type(JsonFieldType.ARRAY).description("필드별 검증 오류(field, code, message)")
                } else {
                    null
                },
            ),
        )

    fun operation(
        identifier: String,
        tag: String,
        summary: String,
        description: String,
        requestSchema: String? = null,
        responseSchema: String? = null,
        vararg snippets: Snippet,
    ): Consumer<EntityExchangeResult<ByteArray>> {
        val details: ResourceSnippetDetails =
            resourceDetails().tag(tag).summary(summary).description(description).also {
                requestSchema?.let { name: String -> it.requestSchema(Schema.schema(name)) }
                responseSchema?.let { name: String -> it.responseSchema(Schema.schema(name)) }
            }
        return document<ByteArray>(
            identifier,
            details,
            requestPreprocessor = preprocessRequest(modifyHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer {access-token}")),
            snippets = snippets,
        )
    }
}
