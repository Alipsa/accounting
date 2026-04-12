<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Momsrapport</h2>
  <div class="metrics">
    <div class="metric">
      <span class="metric-label">Utgående moms</span>
      <span class="metric-value">${outputTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">Ingående moms</span>
      <span class="metric-value">${inputTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">Netto</span>
      <span class="metric-value">${netTotal}</span>
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
