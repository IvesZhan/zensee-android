package com.zensee.android

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupManagementBinding
import com.zensee.android.databinding.ItemGroupManageMemberBinding
import com.zensee.android.model.GroupDetailSnapshot
import com.zensee.android.model.GroupMembershipRole
import kotlin.concurrent.thread

class GroupManagementActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupManagementBinding
    private var snapshot: GroupDetailSnapshot? = null
    private var isDissolving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshot = intent.snapshotExtra()
        binding = ActivityGroupManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.groupManagementToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.group_manage)
        binding.groupManagementToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupManagementRoot, binding.groupManagementToolbar)
        binding.groupDissolveCard.setOnClickListener {
            confirmDissolve()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        loadDetail()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadDetail() {
        val groupId = intent.getStringExtra(GroupDetailActivity.EXTRA_GROUP_ID).orEmpty()
        if (groupId.isBlank()) {
            finish()
            return
        }
        thread(name = "zensee-group-management-detail") {
            val result = runCatching { GroupRepository.fetchGroupDetail(groupId) }
            runOnUiThread {
                result.onSuccess { detail ->
                    if (detail.currentUserRole != GroupMembershipRole.OWNER) {
                        finish()
                        return@runOnUiThread
                    }
                    snapshot = detail
                    render()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun render() {
        val current = snapshot ?: return
        val avatarStyles = GroupUi.buildMemberAvatarStyles(this, current.members, current.currentUserId)
        binding.groupManagementMembersContainer.removeAllViews()
        current.members.filter { it.role != GroupMembershipRole.OWNER }.forEach { member ->
            val itemBinding = ItemGroupManageMemberBinding.inflate(
                LayoutInflater.from(this),
                binding.groupManagementMembersContainer,
                false
            )
            itemBinding.groupManageMemberAvatarText.text = member.nickname.take(1).ifBlank { "禅" }
            GroupUi.applyMemberAvatarStyle(itemBinding.groupManageMemberAvatarText, avatarStyles[member.userId])
            itemBinding.groupManageMemberNameText.text = member.nickname
            itemBinding.groupManageMemberStatusText.text = if (member.didCheckInToday) {
                getString(R.string.group_status_minutes_full, member.totalMinutesToday)
            } else {
                getString(R.string.group_status_not_meditated)
            }
            itemBinding.groupManageMemberActionText.text = getString(R.string.group_remove_action)
            itemBinding.groupManageMemberActionText.setOnClickListener {
                confirmRemove(member.userId, member.nickname)
            }
            binding.groupManagementMembersContainer.addView(itemBinding.root)
        }
        binding.groupDissolveProgress.visibility =
            if (isDissolving) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun confirmRemove(userId: String, nickname: String) {
        val groupId = snapshot?.group?.id ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_remove_member_title))
            .setMessage(getString(R.string.group_remove_member_message, nickname))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.group_remove_action)) { _, _ ->
                thread(name = "zensee-group-remove-member") {
                    val result = runCatching { GroupRepository.removeMember(groupId, userId) }
                    runOnUiThread {
                        result.onSuccess {
                            loadDetail()
                        }.onFailure { error ->
                            Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDissolve() {
        val group = snapshot?.group ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_dissolve_alert_title))
            .setMessage(getString(R.string.group_dissolve_alert_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.group_dissolve_continue)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.group_dissolve_final_title))
                    .setMessage(getString(R.string.group_dissolve_final_message, group.name))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.group_dissolve_confirm)) { _, _ ->
                        dissolveGroup()
                    }
                    .show()
            }
            .show()
    }

    private fun dissolveGroup() {
        val group = snapshot?.group ?: return
        if (isDissolving) return
        isDissolving = true
        render()
        thread(name = "zensee-group-dissolve") {
            val result = runCatching { GroupRepository.dissolveGroup(group) }
            runOnUiThread {
                isDissolving = false
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.group_dissolve_success), Toast.LENGTH_SHORT).show()
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_DISSOLVED, true)
                            .putExtra(MainActivity.GROUP_RESULT_REMOVED_GROUP_ID, group.id)
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, true)
                    )
                    finish()
                }.onFailure { error ->
                    render()
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_DISSOLVED = "extra_group_dissolved"
        const val EXTRA_SNAPSHOT = "extra_group_snapshot"
    }

    private fun Intent.snapshotExtra(): GroupDetailSnapshot? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(EXTRA_SNAPSHOT, GroupDetailSnapshot::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(EXTRA_SNAPSHOT) as? GroupDetailSnapshot
        }
    }
}
