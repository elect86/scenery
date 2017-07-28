package graphics.scenery.backends.vulkan

import cleargl.GLVector
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import graphics.scenery.GeometryType
import graphics.scenery.Node
import graphics.scenery.Settings
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.utils.RingBuffer
import org.lwjgl.system.MemoryUtil.*
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Class to encapsulate Vulkan Renderpasses
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanRenderpass(val name: String, config: RenderConfigReader.RenderConfig,
                       val device: VkDevice,
                       val descriptorPool: Long,
                       val pipelineCache: Long,
                       val memoryProperties: VkPhysicalDeviceMemoryProperties,
                       val vertexDescriptors: ConcurrentHashMap<VulkanRenderer.VertexDataKinds, VulkanRenderer.VertexDescription>): AutoCloseable {

    protected var logger: Logger = LoggerFactory.getLogger("VulkanRenderpass")

    val inputs = ConcurrentHashMap<String, VulkanFramebuffer>()
    val output = ConcurrentHashMap<String, VulkanFramebuffer>()

    var pipelines = ConcurrentHashMap<String, VulkanPipeline>()
    var UBOs = ConcurrentHashMap<String, VulkanUBO>()
    var descriptorSets = ConcurrentHashMap<String, Long>()
    var descriptorSetLayouts = LinkedHashMap<String, Long>()

    var commandBuffer: VulkanCommandBuffer
        get() {
            return commandBufferBacking.get()
        }

        set(b) {
            commandBufferBacking.put(b)
        }

    private var commandBufferBacking = RingBuffer(size = 2,
        default = { VulkanCommandBuffer(device, null, true) })

    var semaphore = -1L

    var passConfig: RenderConfigReader.RenderpassConfig = config.renderpasses.get(name)!!

    var isViewportRenderpass = false
    var commandBufferCount = 2
        set(count) {
            // clean up old backing
            (1..commandBufferBacking.size).forEach { commandBufferBacking.get().close() }
            commandBufferBacking.reset()

            this.isViewportRenderpass = true
            field = count

            commandBufferBacking = RingBuffer(size = count,
                default = { VulkanCommandBuffer(device, null, true) })
        }

    private var currentPosition = 0

    class VulkanMetadata(var descriptorSets: LongBuffer = memAllocLong(4),
                              var vertexBufferOffsets: LongBuffer = memAllocLong(1),
                              var scissor: VkRect2D.Buffer = VkRect2D.calloc(1),
                              var viewport: VkViewport.Buffer = VkViewport.calloc(1),
                              var vertexBuffers: LongBuffer = memAllocLong(1),
                              var instanceBuffers: LongBuffer = memAllocLong(1),
                              var clearValues: VkClearValue.Buffer = VkClearValue.calloc(0),
                              var renderArea: VkRect2D = VkRect2D.calloc(),
                              var renderPassBeginInfo: VkRenderPassBeginInfo = VkRenderPassBeginInfo.calloc(),
                              var uboOffsets: IntBuffer = memAllocInt(16),
                              var eye: IntBuffer = memAllocInt(1)): AutoCloseable {

        override fun close() {
            memFree(descriptorSets)
            memFree(vertexBufferOffsets)
            scissor.free()
            viewport.free()
            memFree(vertexBuffers)
            memFree(instanceBuffers)
            clearValues.free()
            renderArea.free()
            renderPassBeginInfo.free()
            memFree(uboOffsets)
            memFree(eye)
        }

    }

    var vulkanMetadata = VulkanMetadata()

    init {

        val default = VU.createDescriptorSetLayout(device,
            descriptorNum = 3,
            descriptorCount = 1)

        descriptorSetLayouts.put("default", default)

        val lightParameters = VU.createDescriptorSetLayout(
            device,
            listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1)),
            VK_SHADER_STAGE_ALL_GRAPHICS)

        descriptorSetLayouts.put("LightParameters", lightParameters)

        val dslObjectTextures = VU.createDescriptorSetLayout(
            device,
            listOf(Pair(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 6),
                Pair(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1)),
            VK_SHADER_STAGE_ALL_GRAPHICS)

        descriptorSetLayouts.put("ObjectTextures", dslObjectTextures)

        val dslVRParameters = VU.createDescriptorSetLayout(
            device,
            listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1)),
            VK_SHADER_STAGE_ALL_GRAPHICS)

        descriptorSetLayouts.put("VRParameters", dslVRParameters)
    }

    fun initializeInputAttachmentDescriptorSetLayouts() {
        inputs.forEach { inputFramebuffer ->
            // create descriptor set layout that matches the render target
            val dsl = VU.createDescriptorSetLayout(device,
                descriptorNum = inputFramebuffer.value.attachments.count(),
                descriptorCount = 1,
                type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
            )

            val ds = inputFramebuffer.value.outputDescriptorSet

            descriptorSetLayouts.put("inputs-${this.name}", dsl)
            descriptorSets.put("inputs-${this.name}", ds)
        }
    }

    fun initializeShaderParameterDescriptorSetLayouts(settings: Settings) {
        // renderpasses might have parameters set up in their YAML config. These get translated to
        // descriptor layouts, UBOs and descriptor sets
        passConfig.parameters?.let { params ->
            logger.debug("Creating VulkanUBO for $name")
            // create UBO
            val ubo = VulkanUBO(device)

            ubo.name = "ShaderParameters-$name"
            params.forEach { entry ->
                // Entry could be created in Java, so we check for both Java and Kotlin strings
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                val value = if (entry.value is String || entry.value is java.lang.String) {
                    val s = entry.value as String
                    GLVector(*(s.split(",").map { it.trim().trimStart().toFloat() }.toFloatArray()))
                } else if (entry.value is Double) {
                    (entry.value as Double).toFloat()
                } else {
                    entry.value
                }

                val settingsKey = if(entry.key.startsWith("Global")) {
                    "Renderer.${entry.key.substringAfter("Global.")}"
                } else {
                    "Renderer.$name.${entry.key}"
                }

                if(!entry.key.startsWith("Global")) {
                    settings.set(settingsKey, value)
                }

                ubo.add(entry.key, { settings.get(settingsKey) })
            }

            logger.debug("Members are: ${ubo.members()}")
            logger.debug("Allocating VulkanUBO memory now, space needed: ${ubo.getSize()}")

            ubo.createUniformBuffer(memoryProperties)

            // create descriptor set layout
            val dsl = VU.createDescriptorSetLayout(device,
                VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, 1)

            val ds = VU.createDescriptorSet(device, descriptorPool, dsl,
            1, ubo.descriptor!!, type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)

            // populate descriptor set
            ubo.populate()

            UBOs.put("ShaderParameters-$name", ubo)
            descriptorSets.put("ShaderParameters-$name", ds)

            logger.debug("Created DSL $dsl for $name, VulkanUBO has ${params.count()} members")
            descriptorSetLayouts.putIfAbsent("ShaderParameters-$name", dsl)
        }
    }

    fun initializeShaderPropertyDescriptorSetLayout(): Long {
        // this creates a shader property UBO for items marked @ShaderProperty in node
        val alreadyCreated = descriptorSetLayouts.containsKey("ShaderProperties-$name")

        val dsl = if(!alreadyCreated) {
            // create descriptor set layout
            val dsl = VU.createDescriptorSetLayout(
                device,
                listOf(Pair(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1)),
                VK_SHADER_STAGE_ALL_GRAPHICS)

            logger.debug("Created Shader Property DSL $dsl for $name")
            descriptorSetLayouts.putIfAbsent("ShaderProperties-$name", dsl)
            dsl
        } else {
            descriptorSetLayouts.get("ShaderProperties-$name")!!
        }

        // returns a ordered list of the members of the ShaderProperties struct
        return dsl
    }

    fun getShaderPropertyOrder(node: Node): List<String> {
        // this creates a shader property UBO for items marked @ShaderProperty in node
        logger.debug("specs: ${this.pipelines["preferred-${node.name}"]!!.descriptorSpecs}")
        val shaderPropertiesSpec = this.pipelines["preferred-${node.name}"]!!.descriptorSpecs.filter { it.name == "ShaderProperties" }

        if(shaderPropertiesSpec.count() == 0) {
            logger.error("Shader uses no declared shader properties!")
            return emptyList()
        }

        val specs = shaderPropertiesSpec.map { it.members }.flatMap { it.keys }

        // returns a ordered list of the members of the ShaderProperties struct
        return specs.toList()
    }

    fun updateShaderParameters() {
        UBOs.forEach { uboName, ubo ->
            if(uboName.startsWith("ShaderParameters-")) {
                ubo.populate()
            }
        }
    }

    fun updateShaderProperties() {
        UBOs.forEach { uboName, ubo ->
            if(uboName.startsWith("ShaderProperties-")) {
                ubo.populate()
            }
        }
    }

    fun initializeDefaultPipeline() {
        initializePipeline("default", passConfig.shaders.map { VulkanShaderModule(device, "main", VulkanRenderer::class.java, "shaders/" + it) })
    }

    fun initializePipeline(pipelineName: String = "default", shaders: List<VulkanShaderModule>,
                           vertexInputType: VulkanRenderer.VertexDescription = vertexDescriptors.get(VulkanRenderer.VertexDataKinds.coords_normals_texcoords)!!,
                           settings: (VulkanPipeline) -> Any = {}) {
        val p = VulkanPipeline(device, pipelineCache)
        settings.invoke(p)

        val reqDescriptorLayouts = ArrayList<Long>()

        val framebuffer = output.values.first()

        p.addShaderStages(shaders)

        logger.debug("${descriptorSetLayouts.count()} DSLs are available: ${descriptorSetLayouts.keys.joinToString(", ")}")

        val blendMasks = VkPipelineColorBlendAttachmentState.calloc(framebuffer.colorAttachmentCount())
        (0..framebuffer.colorAttachmentCount() - 1).forEach {
            if(passConfig.renderTransparent) {
                blendMasks[it]
                    .blendEnable(true)
                    .colorBlendOp(passConfig.colorBlendOp.toVulkan())
                    .srcColorBlendFactor(passConfig.srcColorBlendFactor.toVulkan())
                    .dstColorBlendFactor(passConfig.dstColorBlendFactor.toVulkan())
                    .alphaBlendOp(passConfig.alphaBlendOp.toVulkan())
                    .srcAlphaBlendFactor(passConfig.srcAlphaBlendFactor.toVulkan())
                    .dstAlphaBlendFactor(passConfig.dstAlphaBlendFactor.toVulkan())
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
            } else {
                blendMasks[it]
                    .blendEnable(false)
                    .colorWriteMask(0xF)
            }
        }

        p.colorBlendState
            .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
            .pNext(MemoryUtil.NULL)
            .pAttachments(blendMasks)

        if (passConfig.type == RenderConfigReader.RenderpassType.quad) {
            p.rasterizationState.cullMode(VK_CULL_MODE_FRONT_BIT)
            p.rasterizationState.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)

            if(logger.isDebugEnabled) {
                logger.debug("DS are: ${p.descriptorSpecs.map { it.name }.joinToString(", ")}")
            }

            // add descriptor specs. at this time, they are expected to be already
            // ordered (which happens at pipeline creation time).
            p.descriptorSpecs.forEach { spec ->
                val dslName = if(spec.name.startsWith("ShaderParameters")) {
                    "ShaderParameters-$name"
                } else if(spec.name.startsWith("inputs")) {
                    "inputs-$name"
                } else if(spec.name.startsWith("Matrices")) {
                    "default"
                } else {
                    spec.name
                }

                val dsl = descriptorSetLayouts.get(dslName)
                if(dsl != null) {
                    logger.debug("Adding DSL for $dslName to required pipeline DSLs")
                    reqDescriptorLayouts.add(dsl)
                } else {
                    logger.error("DSL for $dslName not found, but required by $this!")
                }
            }

            p.createPipelines(framebuffer.renderPass.get(0),
                vertexDescriptors.get(VulkanRenderer.VertexDataKinds.coords_none)!!.state,
                descriptorSetLayouts = reqDescriptorLayouts,
                onlyForTopology = GeometryType.TRIANGLES)
        } else {
            reqDescriptorLayouts.add(descriptorSetLayouts.get("default")!!)
            reqDescriptorLayouts.add(descriptorSetLayouts.get("ObjectTextures")!!)
            reqDescriptorLayouts.add(descriptorSetLayouts.get("VRParameters")!!)

            if(descriptorSetLayouts.containsKey("ShaderProperties-$name")) {
                logger.debug("Adding shader property DSL")
                 reqDescriptorLayouts.add(descriptorSetLayouts["ShaderProperties-$name"]!!)
            }
//            if(descriptorSetLayouts.containsKey("inputs-$name")) {
//                reqDescriptorLayouts.add(descriptorSetLayouts.get("inputs-$name")!!)
//            }
//
            p.createPipelines(framebuffer.renderPass.get(0),
                vertexInputType.state,
                descriptorSetLayouts = reqDescriptorLayouts)
        }

        logger.debug("Prepared pipeline $pipelineName for $name")

        pipelines.put(pipelineName, p)
    }

    fun getOutput(): VulkanFramebuffer {
        val fb = if(isViewportRenderpass) {
            val pos = currentPosition
            currentPosition = (currentPosition + 1).rem(commandBufferCount)

            output["Viewport-$pos"]!!
        } else {
            output.values.first()
        }

        return fb
    }

    fun getReadPosition() = commandBufferBacking.currentReadPosition - 1

    override fun close() {
        output.forEach { it.value.close() }
        pipelines.forEach { it.value.close() }
        descriptorSetLayouts.forEach { vkDestroyDescriptorSetLayout(device, it.value, null) }
        UBOs.forEach { it.value.close() }

        vulkanMetadata.close()

        (1..commandBufferBacking.size).forEach { commandBufferBacking.get().close() }
        commandBufferBacking.reset()

        if(semaphore != -1L) {
            vkDestroySemaphore(device, semaphore, null)
        }
    }

    private fun RenderConfigReader.BlendFactor.toVulkan() = when (this) {
        RenderConfigReader.BlendFactor.Zero -> VK_BLEND_FACTOR_ZERO
        RenderConfigReader.BlendFactor.One -> VK_BLEND_FACTOR_ONE
        RenderConfigReader.BlendFactor.OneMinusSrcAlpha -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
        RenderConfigReader.BlendFactor.SrcAlpha -> VK_BLEND_FACTOR_SRC_ALPHA
    }

    private fun RenderConfigReader.BlendOp.toVulkan() = when (this) {
        RenderConfigReader.BlendOp.add -> VK_BLEND_OP_ADD
        RenderConfigReader.BlendOp.subtract -> VK_BLEND_OP_SUBTRACT
        RenderConfigReader.BlendOp.min -> VK_BLEND_OP_MIN
        RenderConfigReader.BlendOp.max -> VK_BLEND_OP_MAX
        RenderConfigReader.BlendOp.reverse_subtract -> VK_BLEND_OP_REVERSE_SUBTRACT
    }
}
