import { createRouter, createWebHistory } from 'vue-router'
import ReadingApp from '../views/ReadingApp.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/reading' },
    { path: '/reading', name: 'reading', component: ReadingApp }
  ]
})
