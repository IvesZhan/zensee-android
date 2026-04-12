package com.zensee.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupCreateBinding
import kotlin.concurrent.thread

class GroupCreateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupCreateBinding
    private lateinit var submitButtonController: LoadingButtonController
    private var remainingCreateSlots = 3
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureAuthenticated()) return

        binding = ActivityGroupCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.groupCreateToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.group_create_title)
        binding.groupCreateToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupCreateRoot, binding.groupCreateToolbar)
        submitButtonController = LoadingButtonController(
            binding.groupCreateSubmitButton,
            binding.groupCreateSubmitProgress
        )

        binding.groupNameInput.doAfterTextChanged {
            updateSubmitState()
        }
        binding.groupDescriptionInput.doAfterTextChanged { text ->
            binding.groupDescriptionCountText.text = "${text?.length ?: 0}/120"
            updateSubmitState()
        }
        binding.groupCreateSubmitButton.setOnClickListener {
            submit()
        }

        binding.groupDescriptionCountText.text = "0/120"
        loadCreateSlots()
        updateSubmitState()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadCreateSlots() {
        thread(name = "zensee-group-slots") {
            val result = runCatching {
                GroupRepository.fetchMyGroups().count { it.isOwner }
            }
            runOnUiThread {
                val ownedCount = result.getOrDefault(0)
                remainingCreateSlots = (3 - ownedCount).coerceAtLeast(0)
                binding.groupCreateInfoTitle.text =
                    "还可以新建 $remainingCreateSlots 个群组"
                updateSubmitState()
            }
        }
    }

    private fun submit() {
        if (isSubmitting || !canSubmit()) return
        isSubmitting = true
        updateSubmitState()
        val name = binding.groupNameInput.text?.toString().orEmpty()
        val description = binding.groupDescriptionInput.text?.toString().orEmpty()
        thread(name = "zensee-group-create") {
            val result = runCatching {
                GroupRepository.createGroup(name, description)
            }
            runOnUiThread {
                isSubmitting = false
                updateSubmitState()
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.group_create_success), Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { error ->
                    Toast.makeText(this, GroupUi.errorMessage(this, error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun canSubmit(): Boolean {
        val name = binding.groupNameInput.text?.toString()?.trim().orEmpty()
        val description = binding.groupDescriptionInput.text?.toString()?.trim().orEmpty()
        return name.length in 2..24 && description.length <= 120 && remainingCreateSlots > 0
    }

    private fun updateSubmitState() {
        submitButtonController.setLoading(isSubmitting)
        val enabled = !isSubmitting && canSubmit()
        binding.groupCreateSubmitButton.isEnabled = enabled
        binding.groupCreateSubmitButton.alpha = if (isSubmitting || enabled) 1f else 0.55f
    }

    private fun ensureAuthenticated(): Boolean {
        if (AuthManager.state().isAuthenticated) return true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
        return false
    }
}
