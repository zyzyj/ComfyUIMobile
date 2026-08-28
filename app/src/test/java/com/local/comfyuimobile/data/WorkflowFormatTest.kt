package com.local.comfyuimobile.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowFormatTest {
    @Test fun recognizesCanvasFormat() {
        val canvas = JSONObject(
            """
            {"nodes":[{"id":"3","type":"KSampler"}],"links":[],"version":0.4}
            """.trimIndent(),
        )
        assertTrue(WorkflowFormat.isCanvas(canvas))
        assertFalse(WorkflowFormat.isApiPrompt(canvas))
    }

    @Test fun recognizesApiPromptFormat() {
        val api = JSONObject(
            """
            {
              "3": {"class_type": "KSampler", "inputs": {"seed": 1, "steps": 20}},
              "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "x.safetensors"}}
            }
            """.trimIndent(),
        )
        assertFalse(WorkflowFormat.isCanvas(api))
        assertTrue(WorkflowFormat.isApiPrompt(api))
    }

    @Test fun rejectsEmptyAndMixedObjects() {
        assertFalse(WorkflowFormat.isApiPrompt(JSONObject("{}")))
        // 条目缺 class_type 时不能判定为 API 格式。
        val mixed = JSONObject("""{"3":{"inputs":{}},"4":{"class_type":"KSampler","inputs":{}}}""")
        assertFalse(WorkflowFormat.isApiPrompt(mixed))
    }
}
