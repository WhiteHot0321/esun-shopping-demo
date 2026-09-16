import { createApp, h } from 'vue'
import { createPinia } from 'pinia'
import router, { RouterView } from './router'
import './style.css'

createApp({ render: () => h(RouterView) })
  .use(createPinia())
  .use(router)
  .mount('#app')
