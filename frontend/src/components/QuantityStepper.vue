<template>
  <div class="stepper" :class="{ 'stepper--compact': compact }">
    <button type="button" class="stepper__btn" :aria-label="`減少 ${label} 數量`" :disabled="disabled || modelValue <= 0"
      @click="emitValue(modelValue - 1)">−</button>
    <input class="stepper__input" type="number" inputmode="numeric" min="0" :max="max" step="1" :value="modelValue"
      :aria-label="`${label} 購買數量`" :disabled="disabled" @change="onInput($event.target)"
      @focus="$event.target.select()" />
    <button type="button" class="stepper__btn" :aria-label="`增加 ${label} 數量`" :disabled="disabled || modelValue >= max"
      @click="emitValue(modelValue + 1)">+</button>
  </div>
</template>

<script setup>
import { nextTick } from 'vue'

const props = defineProps({
  modelValue: { type: Number, default: 0 },
  max: { type: Number, required: true },
  label: { type: String, default: '商品' },
  compact: Boolean,
  disabled: Boolean
})
const emit = defineEmits(['update:modelValue'])
// Clamping happens in the parent so it can tell the user when a value was adjusted.
const emitValue = (value) => emit('update:modelValue', value)
const onInput = async (target) => {
  emitValue(target.value)
  // If clamping lands on the previous value Vue sees no change and would leave "99" on screen.
  await nextTick()
  target.value = String(props.modelValue)
}
</script>
