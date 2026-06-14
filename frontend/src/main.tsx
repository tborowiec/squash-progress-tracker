import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import i18n from './i18n'
import './index.css'

i18n.on('languageChanged', lng => {
  document.documentElement.lang = lng
})

const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error("Root element '#root' not found")
}

ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
