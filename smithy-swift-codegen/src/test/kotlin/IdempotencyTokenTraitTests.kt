import io.kotest.matchers.string.shouldContainOnlyOnce
import org.junit.jupiter.api.Test
class IdempotencyTokenTraitTests {
    @Test
    fun `generates idempotent middleware`() {
        val context = setupTests("Isolated/idempotencyToken.smithy", "aws.protocoltests.restxml#RestXml")
        val contents = getFileContents(context.manifest, "Sources/RestXml/RestXmlProtocolClient.swift")
        val expectedContents = """
        builder.interceptors.add(ClientRuntime.IdempotencyTokenMiddleware<IdempotencyTokenWithStructureInput, IdempotencyTokenWithStructureOutput>(keyPath: \.token))
"""
        contents.shouldContainOnlyOnce(expectedContents)
    }
    private fun setupTests(smithyFile: String, serviceShapeId: String): TestContext {
        val context = TestContext.initContextFrom(smithyFile, serviceShapeId, MockHTTPRestXMLProtocolGenerator()) { model ->
            model.defaultSettings(serviceShapeId, "RestXml", "2019-12-16", "Rest Xml Protocol")
        }
        context.generator.initializeMiddleware(context.generationCtx)
        context.generator.generateProtocolClient(context.generationCtx)
        context.generationCtx.delegator.flushWriters()
        return context
    }
}
