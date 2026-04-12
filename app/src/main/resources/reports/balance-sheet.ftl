<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Balansrapport</h2>
  <div class="metrics">
    <div class="metric">
      <span class="metric-label">Tillgångar</span>
      <span class="metric-value">${assetTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">Skulder</span>
      <span class="metric-value">${liabilityTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">Eget kapital</span>
      <span class="metric-value">${equityTotal}</span>
    </div>
  </div>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <#list row as cell>
            <td>${cell}</td>
          </#list>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
