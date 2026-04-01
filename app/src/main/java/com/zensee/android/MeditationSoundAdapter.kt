package com.zensee.android

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.zensee.android.databinding.ItemMeditationSoundBinding
import kotlin.math.roundToInt

class MeditationSoundAdapter(
    private val onSoundSelected: (MeditationSound) -> Unit,
    private val onPreviewPressed: (MeditationSound) -> Unit
) : RecyclerView.Adapter<MeditationSoundAdapter.SoundViewHolder>() {

    private val sounds = MeditationSoundCatalog.sounds
    private var selectedSoundKey: String = MeditationSoundCatalog.defaultSound.storageKey
    private var previewingSoundKey: String? = null

    fun updateSelection(selectedSoundKey: String) {
        this.selectedSoundKey = selectedSoundKey
        notifyDataSetChanged()
    }

    fun updatePreview(previewingSoundKey: String?) {
        this.previewingSoundKey = previewingSoundKey
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SoundViewHolder {
        val binding = ItemMeditationSoundBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SoundViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SoundViewHolder, position: Int) {
        holder.bind(sounds[position])
    }

    override fun getItemCount(): Int = sounds.size

    inner class SoundViewHolder(
        private val binding: ItemMeditationSoundBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(sound: MeditationSound) {
            val context = binding.root.context
            val isSelected = sound.storageKey == selectedSoundKey
            val isPreviewing = isSelected && sound.storageKey == previewingSoundKey
            binding.soundTitle.text = sound.displayName
            binding.soundTitle.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.zs_sound_selected_tint else R.color.zs_primary_dark
                )
            )
            binding.soundImage.setImageResource(sound.imageResId(context))
            binding.previewButton.isVisible = isSelected
            binding.previewButton.setImageResource(
                if (isPreviewing) R.drawable.ic_meditation_pause else R.drawable.ic_meditation_play
            )
            binding.previewButton.contentDescription = context.getString(
                if (isPreviewing) R.string.meditation_sound_pause else R.string.meditation_sound_play
            )
            binding.soundImageCard.styleForSelection(context, isSelected)

            binding.soundImageCard.setOnClickListener {
                onSoundSelected(sound)
            }
            binding.previewButton.setOnClickListener {
                onPreviewPressed(sound)
            }
        }
    }
}

private fun MeditationSound.imageResId(context: Context): Int {
    return context.resources.getIdentifier(imageResName, "drawable", context.packageName)
}

private fun MaterialCardView.styleForSelection(context: Context, isSelected: Boolean) {
    strokeWidth = if (isSelected) context.dpToPx(3) else context.dpToPx(1)
    strokeColor = ContextCompat.getColor(
        context,
        if (isSelected) R.color.zs_sound_selected_tint else R.color.zs_sound_card_stroke
    )
}

private fun Context.dpToPx(value: Int): Int {
    return (value * resources.displayMetrics.density).roundToInt()
}
