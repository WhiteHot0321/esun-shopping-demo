import { createRouter, createWebHistory, RouterView } from 'vue-router'
import App from './App.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: App },
    { path: '/shop', component: App },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

export { RouterView }
