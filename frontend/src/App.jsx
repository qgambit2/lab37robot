import { NavLink, Route, Routes } from 'react-router-dom'
import Admin from './screens/Admin.jsx'
import Orders from './screens/Orders.jsx'
import OrderDetail from './screens/OrderDetail.jsx'

export default function App() {
  return (
    <div className="shell">
      <header className="masthead">
        <div className="brand">
          <span className="tick">▮</span> Order Console
        </div>
        <span className="utc-note">all times UTC</span>
        <nav>
          <NavLink to="/" end className={({ isActive }) => (isActive ? 'active' : '')}>
            Admin
          </NavLink>
          <NavLink to="/orders" className={({ isActive }) => (isActive ? 'active' : '')}>
            Orders
          </NavLink>
        </nav>
      </header>
      <Routes>
        <Route path="/" element={<Admin />} />
        <Route path="/orders" element={<Orders />} />
        <Route path="/orders/:id" element={<OrderDetail />} />
      </Routes>
    </div>
  )
}
