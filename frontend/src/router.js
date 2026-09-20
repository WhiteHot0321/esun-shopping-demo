import { createRouter, createWebHistory, RouterView } from 'vue-router'
import App from './App.vue'
import ResetPasswordView from './components/ResetPasswordView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: App },
    { path: '/shop', component: App },
    { path: '/reset-password', component: ResetPasswordView },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

export { RouterView }
